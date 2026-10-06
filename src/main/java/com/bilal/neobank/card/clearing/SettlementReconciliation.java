package com.bilal.neobank.card.clearing;

import com.bilal.neobank.ledger.SystemAccounts;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Three-way reconciliation of a card business day (SPEC-P5-settlement.md section 4), read-only, from NeoBank's own records:
 * what was <b>authorized</b>, what was <b>cleared</b> and what was <b>settled</b>, plus the ledger. Every difference is a
 * {@link Finding} with a plain sentence; things that are merely not finished yet (cleared but not settled) are listed
 * separately and are not findings. Only the last four digits of a card are ever shown.
 */
@Service
public class SettlementReconciliation {

    /** One difference between the records. {@code ours} and {@code theirs} are the two sides in words ("-" when absent). */
    public record Finding(String kind, LocalDate businessDate, String last4, String ours, String theirs, String message) {
    }

    /** A cleared item waiting for a settlement run. */
    public record Waiting(LocalDate businessDate, String last4, String kind, String amount, String merchantName) {
    }

    public record RunRow(String runNumber, String fileName, LocalDate businessDate, LocalDate valueDate, String status, String purchases,
            String refunds, String interchange, String schemeFee, String net, Integer itemCount, String reason, Instant receivedAt) {
    }

    /** Counts and sums for the chosen day (all days when none is chosen). */
    public record Totals(int authorizedOpen, String authorizedOpenAmount, int cleared, String clearedAmount, int settled, String settledAmount,
            int notYetSettled, String notYetSettledAmount, String settlementPayable, boolean ledgerAgrees) {
    }

    public record Report(LocalDate date, List<LocalDate> businessDates, List<RunRow> runs, Totals totals, List<Finding> findings,
            List<Waiting> notYetSettled, boolean allAgree) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public SettlementReconciliation(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Report report(LocalDate date) {
        List<Finding> findings = new ArrayList<>();
        Timestamp now = Timestamp.from(clock.instant());

        // cleared for a different amount than authorized (a tip or a change by the shop)
        findings.addAll(jdbc.sql("""
                select f.business_date, i.last4, a.amount, a.captured_amount from clearing_items i
                join clearing_files f on f.id = i.file_id join authorizations a on a.id = i.authorization_id
                where i.result = 'POSTED' and a.captured_amount is not null and a.captured_amount <> a.amount
                  and (cast(:d as date) is null or f.business_date = :d) order by f.business_date, i.record_no
                """).param("d", date).query((rs, i) -> new Finding("AMOUNT_CHANGED", rs.getObject(1, LocalDate.class), rs.getString(2),
                "authorized " + money(rs.getBigDecimal(3)), "cleared " + money(rs.getBigDecimal(4)),
                "Authorized for " + money(rs.getBigDecimal(3)) + " but cleared for " + money(rs.getBigDecimal(4)) + " (a tip or a change by the shop).")).list());

        // cleared although this bank never approved it
        findings.addAll(jdbc.sql("""
                select f.business_date, i.last4, i.amount from clearing_items i join clearing_files f on f.id = i.file_id
                where i.result = 'FORCE_POSTED' and (cast(:d as date) is null or f.business_date = :d) order by f.business_date, i.record_no
                """).param("d", date).query((rs, i) -> new Finding("CLEARED_WITHOUT_AUTHORIZATION", rs.getObject(1, LocalDate.class), rs.getString(2),
                "no approval", "cleared " + money(rs.getBigDecimal(3)),
                "The shop's bank cleared " + money(rs.getBigDecimal(3)) + " that this bank never approved; it was posted anyway.")).list());

        // items the issuer refused to post
        findings.addAll(jdbc.sql("""
                select f.business_date, i.last4, i.amount, coalesce(i.reason, '') from clearing_items i join clearing_files f on f.id = i.file_id
                where i.result = 'REJECTED' and (cast(:d as date) is null or f.business_date = :d) order by f.business_date, i.record_no
                """).param("d", date).query((rs, i) -> new Finding("REJECTED_AT_ISSUER", rs.getObject(1, LocalDate.class), rs.getString(2),
                "approved?", "cleared " + money(rs.getBigDecimal(3)), "Not posted: " + rs.getString(4) + ".")).list());

        // approved, hold over, never cleared: the shop did not present it
        findings.addAll(jdbc.sql("""
                select a.expires_at, a.last4, a.amount from authorizations a
                where a.response_code = '00' and a.status in ('AUTHORIZED', 'EXPIRED') and a.expires_at <= :now and a.forced = false
                  and (cast(:d as date) is null or cast(a.expires_at at time zone 'UTC' as date) = :d) order by a.expires_at
                """).param("d", date).param("now", now).query((rs, i) -> new Finding("NEVER_CLEARED",
                rs.getObject(1, OffsetDateTime.class).atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate(), rs.getString(2),
                "approved " + money(rs.getBigDecimal(3)), "never cleared",
                "Approved for " + money(rs.getBigDecimal(3)) + " but never presented for clearing: the hold ran out.")).list());

        // statements that were refused or whose figures differ
        findings.addAll(jdbc.sql("""
                select coalesce(business_date, cast(received_at at time zone 'UTC' as date)), status, file_name, coalesce(reason, '')
                from settlement_runs where status in ('DISPUTED', 'REFUSED') and (cast(:d as date) is null or business_date = :d) order by received_at
                """).param("d", date).query((rs, i) -> new Finding("SETTLEMENT_" + rs.getString(2), rs.getObject(1, LocalDate.class), "----",
                "NeoBank's own figures", rs.getString(3), "Statement " + rs.getString(3) + " was " + rs.getString(2).toLowerCase() + ": " + rs.getString(4))).list());

        // matched statements whose ledger postings are not what the statement says
        record Matched(String run, LocalDate date, BigDecimal paidOut) {
        }
        List<Matched> matched = jdbc.sql("""
                select run_number, business_date, purchases - refunds - interchange + scheme_fee from settlement_runs
                where status = 'MATCHED' and (cast(:d as date) is null or business_date = :d) order by received_at
                """).param("d", date).query((rs, i) -> new Matched(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3))).list();
        for (Matched m : matched) {
            BigDecimal posted = jdbc.sql("""
                    select coalesce(sum(e.amount), 0) from ledger_entries e join transactions t on t.id = e.transaction_id
                    where e.account_id = :bank and t.type = 'CARD_SETTLEMENT' and t.description like :prefix
                    """).param("bank", SystemAccounts.SETTLEMENT_BANK).param("prefix", "Card settlement " + m.run + " (%").query(BigDecimal.class).single();
            if (posted.compareTo(m.paidOut) != 0) {
                findings.add(new Finding("SETTLEMENT_NOT_IN_LEDGER", m.date, "----", "statement " + money(m.paidOut), "ledger " + money(posted),
                        "Run " + m.run + " says " + money(m.paidOut) + " was paid out, the ledger says " + money(posted) + "."));
            }
        }

        // cleared but not settled: not an exception, just not finished
        List<Waiting> waiting = jdbc.sql("""
                select f.business_date, i.last4, i.kind, i.amount, i.merchant_name from clearing_items i join clearing_files f on f.id = i.file_id
                where i.settlement_run_id is null and i.result in ('POSTED', 'FORCE_POSTED', 'REFUND_POSTED')
                  and (cast(:d as date) is null or f.business_date = :d) order by f.business_date, i.record_no
                """).param("d", date).query((rs, i) -> new Waiting(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3),
                money(rs.getBigDecimal(4)), rs.getString(5))).list();

        Totals totals = totals(date, now, waiting, findings);
        List<LocalDate> dates = jdbc.sql("select distinct business_date from clearing_files where business_date is not null order by business_date desc limit 60")
                .query(LocalDate.class).list();
        List<RunRow> runs = jdbc.sql("""
                select run_number, file_name, business_date, value_date, status, purchases, refunds, interchange, scheme_fee, net, item_count, reason, received_at
                from settlement_runs order by received_at desc, id desc limit 20
                """).query((rs, i) -> new RunRow(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                rs.getString(5), money(rs.getBigDecimal(6)), money(rs.getBigDecimal(7)), money(rs.getBigDecimal(8)), money(rs.getBigDecimal(9)),
                money(rs.getBigDecimal(10)), (Integer) rs.getObject(11), rs.getString(12), rs.getObject(13, OffsetDateTime.class).toInstant())).list();
        return new Report(date, dates, runs, totals, findings, waiting, findings.isEmpty());
    }

    private Totals totals(LocalDate date, Timestamp now, List<Waiting> waiting, List<Finding> findings) {
        record Count(int n, BigDecimal sum) {
        }
        Count open = jdbc.sql("""
                select count(*), coalesce(sum(amount), 0) from authorizations where status = 'AUTHORIZED' and expires_at > :now
                """).param("now", now).query((rs, i) -> new Count(rs.getInt(1), rs.getBigDecimal(2))).single();
        Count cleared = jdbc.sql("""
                select count(*), coalesce(sum(i.amount), 0) from clearing_items i join clearing_files f on f.id = i.file_id
                where i.result in ('POSTED', 'FORCE_POSTED') and (cast(:d as date) is null or f.business_date = :d)
                """).param("d", date).query((rs, i) -> new Count(rs.getInt(1), rs.getBigDecimal(2))).single();
        Count settled = jdbc.sql("""
                select count(*), coalesce(sum(i.amount), 0) from clearing_items i join clearing_files f on f.id = i.file_id
                where i.result in ('POSTED', 'FORCE_POSTED') and i.settlement_run_id is not null and (cast(:d as date) is null or f.business_date = :d)
                """).param("d", date).query((rs, i) -> new Count(rs.getInt(1), rs.getBigDecimal(2))).single();
        BigDecimal waitingSum = waiting.stream().map(w -> new BigDecimal(w.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payable = jdbc.sql("select balance from accounts where id = :id").param("id", SystemAccounts.CARD_SETTLEMENT).query(BigDecimal.class).single();
        // the card settlement account holds what is cleared and not yet paid out: posted net less the gross of every matched run
        BigDecimal postedNet = jdbc.sql("""
                select coalesce(sum(case when result in ('POSTED', 'FORCE_POSTED') then amount when result = 'REFUND_POSTED' then -amount else 0 end), 0)
                from clearing_items
                """).query(BigDecimal.class).single();
        BigDecimal settledGross = jdbc.sql("select coalesce(sum(purchases - refunds), 0) from settlement_runs where status = 'MATCHED'")
                .query(BigDecimal.class).single();
        boolean ledgerAgrees = payable.compareTo(postedNet.subtract(settledGross)) == 0;
        if (!ledgerAgrees) {
            findings.add(new Finding("LEDGER_MISMATCH", null, "----", "items " + money(postedNet.subtract(settledGross)), "ledger " + money(payable),
                    "The card settlement account holds " + money(payable) + " but the cleared items less the settled ones come to "
                            + money(postedNet.subtract(settledGross)) + "."));
        }
        return new Totals(open.n, money(open.sum), cleared.n, money(cleared.sum), settled.n, money(settled.sum), waiting.size(), money(waitingSum),
                money(payable), ledgerAgrees);
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
