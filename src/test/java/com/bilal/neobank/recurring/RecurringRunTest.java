package com.bilal.neobank.recurring;

import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Running standing orders. The clock is moved to create orders "in the past"; money moves through the real transfer
 * code. Money transactions are always stamped with the real time, so the clock never moves past the real "now".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class RecurringRunTest extends AnalyticsTestBase {

    @Autowired
    MutableClock mutableClock;

    @Autowired
    RecurringExecutor executor;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    RecurringRepository repository;

    private Instant now;

    @BeforeEach
    void realNow() {
        now = Instant.now();
        mutableClock.set(now);
    }

    private void clockDaysAgo(int days) {
        mutableClock.set(now.minus(days, ChronoUnit.DAYS));
    }

    private String createOrder(String token, Acc source, String iban, String amount, String frequency,
            LocalDate start, LocalDate end, String extra) throws Exception {
        String json = mockMvc.perform(post("/api/recurring-payments").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceAccountId":"%s","destinationIban":"%s","amount":%s,"frequency":"%s","startDate":"%s",
                         "endDate":%s%s}
                        """.formatted(source.id(), iban, amount, frequency, start,
                        end == null ? "null" : "\"" + end + "\"", extra)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String order(String token, Acc source, Acc to, String amount, String frequency, LocalDate start)
            throws Exception {
        return createOrder(token, source, to.iban(), amount, frequency, start, null, "");
    }

    private void fund(Acc account, String amount) {
        depositOn(account, amount, now.minus(1, ChronoUnit.DAYS));
    }

    private BigDecimal balance(Acc account) {
        return jdbc.queryForObject("select balance from accounts where id = ?::uuid", BigDecimal.class, account.id());
    }

    private int transfers() {
        return jdbc.queryForObject("select count(*) from transactions where type = 'TRANSFER'", Integer.class);
    }

    private List<Map<String, Object>> runs(String id) {
        return jdbc.queryForList("select run_date::text d, status, failure_code from recurring_runs "
                + "where payment_id = ?::uuid order by run_date", id);
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select status, auto_paused, consecutive_failures, next_run_date::text next, occurrence "
                + "from recurring_payments where id = ?::uuid", id);
    }

    // ---- the happy path ---------------------------------------------------------------------------------------

    @Test
    void aDueOrderMovesMoneyThroughTheNormalTransferAndSchedulesTheNextMonth() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "100.00");
        String id = createOrder(alice, a, b.iban(), "25.00", "MONTHLY", today(), null,
                ",\"description\":\"Gym\",\"categoryId\":\"%s\"".formatted(categoryId("Groceries")));

        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 1, 0));

        assertThat(balance(a)).isEqualByComparingTo("75.00");
        assertThat(balance(b)).isEqualByComparingTo("25.00");
        var tx = jdbc.queryForMap("select initiated_by::text u, description, category_id, amount, idempotency_key k "
                + "from transactions where type = 'TRANSFER'");
        assertThat(tx.get("u")).isEqualTo(a.userId());
        assertThat(tx.get("description")).isEqualTo("Gym");
        assertThat(tx.get("category_id")).isEqualTo(categoryId("Groceries"));
        assertThat(tx.get("k")).isEqualTo("rec-" + id + "-" + today().toString().replace("-", ""));
        assertThat(runs(id)).extracting(r -> r.get("status")).containsExactly("SUCCESS");
        assertThat(jdbc.queryForObject("select transaction_id is not null from recurring_runs where payment_id = ?::uuid",
                Boolean.class, id)).isTrue();
        assertThat(row(id)).containsEntry("next", today().plusMonths(1).toString()).containsEntry("status", "ACTIVE")
                .containsEntry("consecutive_failures", 0);
        assertBooksBalance();

        // nothing is due any more
        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 0, 0));
        assertThat(transfers()).isEqualTo(1);
    }

    @Test
    void anOrderThatStartsInTheFutureDoesNothingYet() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "100.00");
        order(alice, a, b, "25.00", "WEEKLY", today().plusDays(1));

        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 0, 0));
        assertThat(transfers()).isZero();
        assertThat(balance(a)).isEqualByComparingTo("100.00");
    }

    @Test
    void aMonthlyOrderStartedOnTheThirtyFirstRunsOnTheLastDayOfShortMonths() throws Exception {
        mutableClock.set(Instant.parse("2026-01-31T10:00:00Z"));
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "100.00");
        String id = order(alice, a, b, "10.00", "MONTHLY", LocalDate.of(2026, 1, 31));

        mutableClock.set(Instant.parse("2026-03-31T10:00:00Z")); // the app was "down" for two months

        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 3, 0));
        assertThat(runs(id)).extracting(r -> r.get("d")).containsExactly("2026-01-31", "2026-02-28", "2026-03-31");
        assertThat(row(id)).containsEntry("next", "2026-04-30");
        assertThat(balance(b)).isEqualByComparingTo("30.00");
        assertBooksBalance();
    }

    @Test
    void theLastRunBeforeTheEndDateCompletesTheOrder() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "100.00");
        String id = createOrder(alice, a, b.iban(), "10.00", "MONTHLY", today(), today().plusDays(10), "");

        assertThat(executor.runAllDue().succeeded()).isEqualTo(1);

        assertThat(row(id)).containsEntry("status", "COMPLETED").containsEntry("next", null);
        clockDaysAgo(-40); // far in the future: still nothing
        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 0, 0));
        assertThat(transfers()).isEqualTo(1);
    }

    // ---- failures ---------------------------------------------------------------------------------------------

    @Test
    void aFailedRunIsRecordedConsumesTheDateAndIsNotRetried() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "5.00");
        String id = order(alice, a, b, "25.00", "WEEKLY", today());

        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 0, 1));

        assertThat(runs(id)).singleElement().satisfies(r -> {
            assertThat(r.get("status")).isEqualTo("FAILED");
            assertThat(r.get("failure_code")).isEqualTo("INSUFFICIENT_FUNDS");
        });
        assertThat(row(id)).containsEntry("consecutive_failures", 1).containsEntry("status", "ACTIVE")
                .containsEntry("next", today().plusWeeks(1).toString());
        assertThat(balance(a)).isEqualByComparingTo("5.00");
        assertThat(transfers()).isZero();
        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 0, 0)); // no retry
    }

    @Test
    void aSuccessfulRunResetsTheFailureCounter() throws Exception {
        clockDaysAgo(14);
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "70.00");
        String id = order(alice, a, b, "60.00", "WEEKLY", today());

        assertThat(executor.runAllDue().succeeded()).isEqualTo(1); // day 0: 70 -> 10
        clockDaysAgo(7);
        assertThat(executor.runAllDue().failed()).isEqualTo(1);    // day 7: 10 < 60
        assertThat(row(id)).containsEntry("consecutive_failures", 1);
        fund(a, "100.00");
        clockDaysAgo(0);
        assertThat(executor.runAllDue().succeeded()).isEqualTo(1); // day 14: 110 -> 50

        assertThat(row(id)).containsEntry("consecutive_failures", 0).containsEntry("status", "ACTIVE");
        assertThat(runs(id)).extracting(r -> r.get("status")).containsExactly("SUCCESS", "FAILED", "SUCCESS");
        assertBooksBalance();
    }

    @Test
    void theDailyLimitStillAppliesToEveryCatchUpRun() throws Exception {
        clockDaysAgo(14);
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "10000.00");
        String id = order(alice, a, b, "3000.00", "WEEKLY", today());
        clockDaysAgo(0);

        // three weekly dates are due at once, but only 5000 may go to other customers per day: 3000 + 3000 > 5000
        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 1, 2));

        assertThat(runs(id)).extracting(r -> r.get("status")).containsExactly("SUCCESS", "FAILED", "FAILED");
        assertThat(runs(id).get(1).get("failure_code")).isEqualTo("DAILY_LIMIT_EXCEEDED");
        assertThat(balance(b)).isEqualByComparingTo("3000.00");
        assertThat(row(id)).containsEntry("consecutive_failures", 2).containsEntry("status", "ACTIVE");
    }

    @Test
    void threeFailuresInARowPauseTheOrderAndResumingRestartsIt() throws Exception {
        clockDaysAgo(21);
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        String id = order(alice, a, b, "10.00", "WEEKLY", today()); // never funded
        clockDaysAgo(0);

        // four dates are due (-21, -14, -7, 0), but the order stops itself after the third failure
        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 0, 3));
        assertThat(row(id)).containsEntry("status", "PAUSED").containsEntry("auto_paused", true)
                .containsEntry("consecutive_failures", 3).containsEntry("next", today().toString());
        assertThat(runs(id)).hasSize(3);
        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 0, 0)); // paused: not run

        fund(a, "100.00");
        var resumed = mockMvc.perform(post("/api/recurring-payments/" + id + "/resume")
                .header("Authorization", alice)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(resumed, "$.status")).isEqualTo("ACTIVE");
        assertThat((Boolean) JsonPath.read(resumed, "$.autoPaused")).isFalse();
        assertThat((Integer) JsonPath.read(resumed, "$.consecutiveFailures")).isZero();

        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 1, 0)); // today's date
        assertThat(balance(b)).isEqualByComparingTo("10.00");
    }

    @Test
    void resumingDoesNotPayTheDatesThatPassedWhilePaused() throws Exception {
        clockDaysAgo(21);
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "100.00");
        String id = order(alice, a, b, "10.00", "WEEKLY", today());
        mockMvc.perform(post("/api/recurring-payments/" + id + "/pause").header("Authorization", alice))
                .andExpect(status().isOk());
        clockDaysAgo(0); // three weekly dates went by

        mockMvc.perform(post("/api/recurring-payments/" + id + "/resume").header("Authorization", alice))
                .andExpect(status().isOk());

        assertThat(row(id)).containsEntry("next", today().toString());
        assertThat(executor.runAllDue().succeeded()).isEqualTo(1); // only today's, not the three missed ones
        assertThat(balance(b)).isEqualByComparingTo("10.00");
    }

    @Test
    void aFrozenRecipientFailsTheRunWithoutBreakingAnything() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "100.00");
        String id = order(alice, a, b, "10.00", "WEEKLY", today());
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", b.id());

        assertThat(executor.runAllDue().failed()).isEqualTo(1);

        assertThat(runs(id).get(0).get("failure_code")).isEqualTo("RECIPIENT_UNAVAILABLE");
        assertThat(balance(a)).isEqualByComparingTo("100.00");
    }

    @Test
    void oneCustomersFailureDoesNotStopAnotherCustomersOrder() throws Exception {
        String alice = tokenFor("alice@example.com");
        String carol = tokenFor("carol@example.com");
        Acc a = open(alice, "CHECKING"); // empty: will fail
        Acc c = open(carol, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(c, "100.00");
        order(alice, a, b, "10.00", "WEEKLY", today());
        order(carol, c, b, "10.00", "WEEKLY", today());

        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 1, 1));
        assertThat(balance(b)).isEqualByComparingTo("10.00");
    }

    // ---- stopped orders ---------------------------------------------------------------------------------------

    @Test
    void pausedAndCancelledOrdersNeverRun() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "100.00");
        String paused = order(alice, a, b, "10.00", "WEEKLY", today());
        String cancelled = order(alice, a, b, "20.00", "WEEKLY", today());
        mockMvc.perform(post("/api/recurring-payments/" + paused + "/pause").header("Authorization", alice))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/recurring-payments/" + cancelled).header("Authorization", alice))
                .andExpect(status().isNoContent());

        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 0, 0));
        assertThat(transfers()).isZero();
    }

    // ---- exactly once -----------------------------------------------------------------------------------------

    @Test
    void ifTheJobDiesAfterTheMoneyMovedTheNextPassDoesNotPayAgain() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "100.00");
        String id = order(alice, a, b, "25.00", "WEEKLY", today());
        // the transfer commits, then recording the run blows up (simulated crash between the two steps)
        doThrow(new IllegalStateException("simulated crash")).doCallRealMethod().when(repository)
                .insertRun(any(), any(), any(), any(), any(), any(), any());

        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 0, 0));
        assertThat(transfers()).isEqualTo(1);              // the money did move
        assertThat(runs(id)).isEmpty();                    // but nothing was recorded
        assertThat(row(id)).containsEntry("next", today().toString()); // so the date is still due

        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(false, 1, 0));
        assertThat(transfers()).isEqualTo(1);              // the retry replayed the same idempotency key
        assertThat(balance(a)).isEqualByComparingTo("75.00");
        assertThat(balance(b)).isEqualByComparingTo("25.00");
        assertThat(runs(id)).extracting(r -> r.get("status")).containsExactly("SUCCESS");
        assertBooksBalance();
    }

    @Test
    void manyInstancesRunningAtOnceMoveTheMoneyOnce() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "100.00");
        String id = order(alice, a, b, "25.00", "WEEKLY", today());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RecurringExecutor.RunSummary>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return executor.runAllDue();
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<RecurringExecutor.RunSummary> result : results) {
            succeeded += result.get(60, TimeUnit.SECONDS).succeeded();
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(transfers()).isEqualTo(1);
        assertThat(runs(id)).hasSize(1);
        assertThat(balance(a)).isEqualByComparingTo("75.00");
        assertBooksBalance();
    }

    @Test
    void aSecondInstanceSkipsTheRunWhileAnotherHoldsTheLock() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        fund(a, "100.00");
        order(alice, a, b, "25.00", "WEEKLY", today());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        Future<?> holder = other.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("select pg_advisory_xact_lock(" + RecurringExecutor.ADVISORY_LOCK_KEY + ")");
            holding.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(executor.runAllDue()).isEqualTo(new RecurringExecutor.RunSummary(true, 0, 0));
        assertThat(transfers()).isZero();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        other.shutdown();
        assertThat(executor.runAllDue().succeeded()).isEqualTo(1);
    }
}
