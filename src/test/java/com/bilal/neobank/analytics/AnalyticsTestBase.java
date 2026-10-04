package com.bilal.neobank.analytics;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.ledger.LedgerReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Customers and accounts are created through the real APIs. Money movements that must happen on CONTROLLED DATES are
 * written straight into the ledger tables (the ledger service cannot backdate), always as a balanced pair of entries
 * with a consistent running balance, so the reconciliation check still holds.
 */
public abstract class AnalyticsTestBase {

    protected static final String PASSWORD = "S3curepass12";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected DatabaseCleaner cleaner;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected LedgerReconciliationService reconciliation;

    @Autowired
    protected Clock clock;

    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    protected void clean() {
        cleaner.clean();
    }

    protected record Acc(String id, String iban, String userId) {
    }

    protected LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    protected String tokenFor(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Test","lastName":"User","email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andExpect(status().isCreated());
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    protected Acc open(String token, String type) throws Exception {
        String json = mockMvc.perform(post("/api/accounts").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"%s\"}".formatted(type)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String userId = jdbc.queryForObject("select user_id::text from accounts where id = ?::uuid", String.class, id);
        return new Acc(id, json.replaceAll(".*\"iban\":\"([^\"]+)\".*", "$1"), userId);
    }

    protected UUID categoryId(String name) {
        return jdbc.queryForObject("select id from categories where name = ?", UUID.class, name);
    }

    /** A bank deposit on a chosen date (the bank funding account pays). */
    protected void depositOn(Acc to, String amount, Instant when) {
        move(UUID.fromString("00000000-0000-0000-0000-000000000001"), UUID.fromString(to.id()), to.userId(), amount,
                when, "DEPOSIT", null, null);
    }

    /** A transfer on a chosen date, optionally with a spending category. */
    protected void transferOn(Acc from, Acc to, String amount, Instant when, String category) {
        move(UUID.fromString(from.id()), UUID.fromString(to.id()), from.userId(), amount, when, "TRANSFER",
                category == null ? null : categoryId(category), "t");
    }

    private void move(UUID source, UUID destination, String initiator, String amount, Instant when, String type,
            UUID category, String description) {
        BigDecimal value = new BigDecimal(amount);
        int n = sequence.incrementAndGet();
        UUID transactionId = UUID.randomUUID();
        BigDecimal sourceAfter = balance(source).subtract(value);
        BigDecimal destinationAfter = balance(destination).add(value);
        OffsetDateTime at = OffsetDateTime.ofInstant(when, ZoneOffset.UTC);
        jdbc.update("""
                insert into transactions (id, type, initiated_by, idempotency_key, request_hash, description,
                                          source_account_id, destination_account_id, amount, category_id, created_at)
                values (?, ?, ?::uuid, ?, 'test', ?, ?, ?, ?, ?, ?)
                """, transactionId, type, initiator, "test-seed-" + n, description, source, destination, value,
                category, at);
        jdbc.update("insert into ledger_entries (transaction_id, account_id, amount, balance_after, created_at) "
                + "values (?, ?, ?, ?, ?)", transactionId, source, value.negate(), sourceAfter, at);
        jdbc.update("insert into ledger_entries (transaction_id, account_id, amount, balance_after, created_at) "
                + "values (?, ?, ?, ?, ?)", transactionId, destination, value, destinationAfter, at);
        jdbc.update("update accounts set balance = ? where id = ?", sourceAfter, source);
        jdbc.update("update accounts set balance = ? where id = ?", destinationAfter, destination);
    }

    private BigDecimal balance(UUID account) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, account);
    }

    protected Instant daysAgo(int days, int hour) {
        return today().minusDays(days).atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    protected Instant onDay(LocalDate day, int hour) {
        return day.atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    protected ResultActions api(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", token));
    }

    protected void assertBooksBalance() {
        var report = reconciliation.check();
        assertThat(report.totalOfAllEntries()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.accountsWithWrongCachedBalance()).isEmpty();
        assertThat(report.customerAccountsBelowZero()).isEmpty();
    }
}
