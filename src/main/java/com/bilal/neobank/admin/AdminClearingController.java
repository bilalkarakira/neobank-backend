package com.bilal.neobank.admin;

import com.bilal.neobank.card.clearing.ClearingIngestService;
import com.bilal.neobank.ledger.SystemAccounts;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Admin view of card clearing: the files received, how every item was decided, what customers owe, and whether the books
 * agree with the items. ADMIN only (SecurityConfig guards /api/admin/**). Shows the last four digits only.
 */
@RestController
public class AdminClearingController {

    public static final int MAX_ITEMS = 100;
    private static final Set<String> RESULTS = Set.of("POSTED", "FORCE_POSTED", "REFUND_POSTED", "DUPLICATE", "REJECTED",
            "CHARGEBACK_SENT", "CHARGEBACK_POSTED", "CHARGEBACK_REFUSED", "REPRESENTMENT_POSTED");

    public record FileRow(String fileName, String networkFileId, String status, String reason, int itemCount, String controlTotal,
            Instant receivedAt, boolean ackWritten) {
    }

    public record ItemRow(String fileName, int recordNo, String kind, String amount, String last4, String merchantName, String result,
            String reason, String covered, String shortfall, boolean forced, Instant createdAt) {
    }

    public record ReceivableRow(String email, String last4, String amount, String outstanding, Instant createdAt) {
    }

    /** {@code booksMatch}: the card settlement account equals posted purchases minus refunds minus what settlement has paid out, and the receivable account equals what is open. */
    public record Totals(String receivableOutstanding, String settlementPayable, String postedNet, String receivableAccount,
            boolean booksMatch) {
    }

    public record Overview(boolean enabled, List<FileRow> files, List<ItemRow> items, List<ReceivableRow> receivables, Totals totals) {
    }

    public record IngestResult(int filesProcessed, int filesRefused, int itemsPosted, int acknowledgementsWritten) {
    }

    private final JdbcClient jdbc;
    private final ClearingIngestService ingest;
    private final AdminRepository adminRepository;
    private final Clock clock;

    public AdminClearingController(JdbcClient jdbc, ClearingIngestService ingest, AdminRepository adminRepository, Clock clock) {
        this.jdbc = jdbc;
        this.ingest = ingest;
        this.adminRepository = adminRepository;
        this.clock = clock;
    }

    @GetMapping("/api/admin/clearing")
    @Transactional(readOnly = true)
    public Overview overview(@RequestParam(required = false) String result) {
        if (result != null && !RESULTS.contains(result)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "result must be one of " + RESULTS);
        }
        List<FileRow> files = jdbc.sql("""
                select file_name, network_file_id, status, reason, item_count, control_total, received_at, ack_written
                from clearing_files order by received_at desc, id desc limit 20
                """).query((rs, i) -> new FileRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                money(BigDecimal.valueOf(rs.getLong(6), 2)), rs.getObject(7, OffsetDateTime.class).toInstant(), rs.getBoolean(8))).list();
        List<ItemRow> items = jdbc.sql("""
                select coalesce(f.file_name, df.file_name, 'dispute'), i.record_no, i.kind, i.amount, i.last4, i.merchant_name, i.result, i.reason, i.covered, i.shortfall,
                       coalesce(a.forced, false), i.created_at
                from clearing_items i left join clearing_files f on f.id = i.file_id left join dispute_files df on df.id = i.dispute_file_id
                left join authorizations a on a.id = i.authorization_id
                where (cast(:result as varchar) is null or i.result = :result)
                order by i.created_at desc, i.record_no desc limit :limit
                """).param("result", result).param("limit", MAX_ITEMS).query((rs, i) -> new ItemRow(rs.getString(1), rs.getInt(2),
                rs.getString(3), money(rs.getBigDecimal(4)), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                money(rs.getBigDecimal(9)), money(rs.getBigDecimal(10)), rs.getBoolean(11), rs.getObject(12, OffsetDateTime.class).toInstant())).list();
        List<ReceivableRow> receivables = jdbc.sql("""
                select u.email, coalesce(c.last4, '----'), r.amount, r.outstanding, r.created_at
                from card_receivables r join users u on u.id = r.user_id
                left join clearing_items i on i.id = r.clearing_item_id left join cards c on c.id = i.card_id
                where r.outstanding > 0 order by r.created_at desc, r.id desc limit 50
                """).query((rs, i) -> new ReceivableRow(rs.getString(1), rs.getString(2), money(rs.getBigDecimal(3)),
                money(rs.getBigDecimal(4)), rs.getObject(5, OffsetDateTime.class).toInstant())).list();
        return new Overview(ingest.enabled(), files, items, receivables, totals());
    }

    private Totals totals() {
        BigDecimal outstanding = jdbc.sql("select coalesce(sum(outstanding), 0) from card_receivables").query(BigDecimal.class).single();
        BigDecimal settlement = jdbc.sql("select balance from accounts where id = :id").param("id", SystemAccounts.CARD_SETTLEMENT)
                .query(BigDecimal.class).single();
        BigDecimal receivableAccount = jdbc.sql("select balance from accounts where id = :id").param("id", SystemAccounts.CARD_RECEIVABLE)
                .query(BigDecimal.class).single();
        BigDecimal postedNet = jdbc.sql("""
                select coalesce(sum(case when result in ('POSTED', 'FORCE_POSTED', 'REPRESENTMENT_POSTED') then amount
                                         when result in ('REFUND_POSTED', 'CHARGEBACK_SENT', 'CHARGEBACK_POSTED') then -amount else 0 end), 0) from clearing_items
                """).query(BigDecimal.class).single();
        // settlement pays the card settlement account down by the gross it settled: what is left is cleared and not yet settled
        BigDecimal settledGross = jdbc.sql("select coalesce(sum(purchases - refunds), 0) from settlement_runs where status = 'MATCHED'").query(BigDecimal.class).single();
        boolean match = settlement.compareTo(postedNet.subtract(settledGross)) == 0 && receivableAccount.compareTo(outstanding.negate()) == 0;
        return new Totals(money(outstanding), money(settlement), money(postedNet), money(receivableAccount), match);
    }

    /** Looks for new clearing files now (the background job does the same every few seconds). Recorded in the audit log. */
    @PostMapping("/api/admin/clearing/ingest")
    public IngestResult runIngest(@AuthenticationPrincipal Jwt jwt) {
        ClearingIngestService.Summary summary = ingest.ingest();
        adminRepository.insertAction(UUID.fromString(jwt.getSubject()), "RUN_CLEARING_INGEST", null, null,
                "files=%d refused=%d posted=%d acks=%d".formatted(summary.filesProcessed(), summary.filesRefused(),
                        summary.itemsPosted(), summary.acknowledgementsWritten()), clock.instant().atOffset(java.time.ZoneOffset.UTC));
        return new IngestResult(summary.filesProcessed(), summary.filesRefused(), summary.itemsPosted(), summary.acknowledgementsWritten());
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
