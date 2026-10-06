package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.card.clearing.ClearingPoster;
import com.bilal.neobank.ledger.LedgerService;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Helpers for the clearing tests: real authorizations through the issuer socket, presentments built from them, direct posting. */
abstract class ClearingTestBase extends IssuerTestBase {

    protected static final UUID SETTLEMENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    protected static final UUID RECEIVABLE = UUID.fromString("00000000-0000-0000-0000-000000000006");

    @Autowired
    protected ClearingPoster poster;

    @Autowired
    protected LedgerService ledger;

    private final AtomicInteger files = new AtomicInteger();

    /** Authorizes a purchase for real and returns the request that was sent. */
    protected Message authorized(Fixture f, String amount) throws Exception {
        Message request = purchase(f, amount);
        try (Terminal terminal = new Terminal().signedOn()) {
            org.assertj.core.api.Assertions.assertThat(code(terminal.exchange(request))).isEqualTo("00");
        }
        return request;
    }

    protected String authCodeOf(Message request) {
        return jdbc.queryForObject("select auth_code from authorizations where stan = ? and rrn = ?", String.class, request.get(11), request.get(37));
    }

    /** The first presentment (1240) of an authorized request, for the FINAL amount. */
    protected Message presentment(Message request, String finalAmount) {
        Message m = Message.of("1240").set(2, request.get(2)).set(3, "000000").set(4, cents(finalAmount)).set(7, request.get(7))
                .set(11, request.get(11)).set(12, request.get(12)).set(13, request.get(13)).set(14, request.get(14))
                .set(18, request.get(18)).set(32, request.get(32)).set(37, request.get(37)).set(41, request.get(41))
                .set(42, request.get(42)).set(43, request.get(43)).set(49, request.get(49));
        String code = jdbc.query("select auth_code from authorizations where stan = ? and rrn = ?", rs -> rs.next() ? rs.getString(1) : null,
                request.get(11), request.get(37));
        return m.set(38, code == null ? "FORCED" : code);
    }

    /** A refund presentment: own STAN and RRN, no authorization code. */
    protected Message refundPresentment(Fixture f, String amount) {
        String stan = nextStan();
        Message m = purchaseWith(f.pan(), f.expiry(), amount, stan, "%012d".formatted(900_000L + Long.parseLong(stan)));
        return Message.of("1240").set(2, m.get(2)).set(3, "200000").set(4, m.get(4)).set(7, m.get(7)).set(11, m.get(11)).set(12, m.get(12))
                .set(13, m.get(13)).set(14, m.get(14)).set(18, m.get(18)).set(32, m.get(32)).set(37, m.get(37)).set(41, m.get(41))
                .set(42, m.get(42)).set(43, m.get(43)).set(49, m.get(49));
    }

    /** The business date of the clearing files made by {@link #newFile()}. */
    protected static final java.time.LocalDate DAY = java.time.LocalDate.of(2026, 10, 2); // a Friday in the past

    protected UUID newFile() {
        return newFile(DAY);
    }

    protected UUID newFile(java.time.LocalDate businessDate) {
        UUID id = UUID.randomUUID();
        int n = files.incrementAndGet();
        jdbc.update("insert into clearing_files (id, file_name, network_file_id, status, received_at, business_date) values (?, ?, ?, 'RECEIVED', ?, ?)", id,
                "T%07d.clr".formatted(n), "T%07d".formatted(n), Timestamp.from(java.time.Instant.now()), businessDate);
        return id;
    }

    protected ClearingPoster.Result present(Message presentment) {
        return poster.post(newFile(), 1, presentment);
    }

    protected ClearingPoster.Result present(Message presentment, java.time.LocalDate businessDate) {
        return poster.post(newFile(businessDate), 1, presentment);
    }

    protected BigDecimal balanceOf(UUID account) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, account);
    }

    protected BigDecimal balanceOf(Fixture f) {
        return balanceOf(UUID.fromString(f.account().id()));
    }

    protected BigDecimal owed(Fixture f) {
        return jdbc.queryForObject("select coalesce(sum(outstanding), 0) from card_receivables where account_id = ?::uuid", BigDecimal.class, f.account().id());
    }

    /** The card transactions in the order they were made, as "TYPE amount". */
    protected java.util.List<String> cardTransactions() {
        return jdbc.queryForList("select type || ' ' || round(amount, 2) from transactions where type like 'CARD_%' order by created_at, type", String.class);
    }

    protected String authorizationState(Message request) {
        return jdbc.queryForObject("select status || '/' || coalesce(captured_amount::text, '-') || '/' || forced from authorizations where stan = ? and rrn = ?",
                String.class, request.get(11), request.get(37));
    }
}
