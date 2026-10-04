package com.bilal.neobank.interest;

import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.SystemAccounts;
import com.bilal.neobank.ledger.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;

/**
 * Savings interest end to end. Dates are controlled: the clock is moved, and account history is inserted on exact
 * dates. The expected amounts are worked out by hand (see each test), not by calling the production formulas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class InterestServiceTest extends AnalyticsTestBase {

    @Autowired
    InterestService service;

    @Autowired
    InterestRepository repository;

    @Autowired
    MutableClock mutableClock;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    LedgerService ledger;

    @BeforeEach
    void fixTheDate() {
        mutableClock.set(Instant.parse("2026-10-15T12:00:00Z")); // completed months: up to and including September
    }

    private Acc savings(String token, String openedOn) throws Exception {
        Acc account = open(token, "SAVINGS");
        jdbc.update("update accounts set created_at = ?::timestamptz where id = ?::uuid", openedOn + "T00:00:00Z",
                account.id());
        return account;
    }

    private BigDecimal balance(UUID account) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, account);
    }

    private BigDecimal balance(Acc account) {
        return balance(UUID.fromString(account.id()));
    }

    private InterestRepository.Accrual accrual(Acc account, String period) {
        return repository.list(UUID.fromString(account.id())).stream().filter(a -> a.period().equals(period))
                .findFirst().orElseThrow(() -> new AssertionError("no accrual for " + period));
    }

    private int interestTransactions() {
        return jdbc.queryForObject("select count(*) from transactions where type = 'INTEREST'", Integer.class);
    }

    // ---- the calculation ---------------------------------------------------------------------------------------

    @Test
    void creditsTheAverageDailyBalanceTimesTheMonthlyRateAndCatchesUpOnQuietMonths() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc s = savings(alice, "2026-07-01");
        depositOn(s, "1000.00", Instant.parse("2026-09-01T00:10:00Z"));   // 15 days at 1000 ...
        depositOn(s, "500.00", Instant.parse("2026-09-16T12:00:00Z"));    // ... then 15 days at 1500

        InterestService.RunSummary summary = service.accrueAllDue();

        // average = (15 x 1000 + 15 x 1500) / 30 = 1250.00; monthly rate for 3.5% APY = 0.28709%; 1250 x 0.0028709 = 3.59
        assertThat(summary).isEqualTo(new InterestService.RunSummary(false, 1, 2, 0));
        InterestRepository.Accrual september = accrual(s, "2026-09");
        assertThat(september.status()).isEqualTo(AccrualStatus.CREDITED);
        assertThat(september.averageBalance()).isEqualByComparingTo("1250.00");
        // worked out by hand: ln(1.035)/12 = 0.00286678556, exp(...) - 1 = 0.0028708987
        assertThat(september.monthlyRate()).isEqualByComparingTo("0.0028708987");
        assertThat(september.amount()).isEqualByComparingTo("3.59");
        // July and August: the account existed but held nothing, so they are recorded and skipped
        assertThat(accrual(s, "2026-07").status()).isEqualTo(AccrualStatus.SKIPPED_ZERO);
        assertThat(accrual(s, "2026-08").status()).isEqualTo(AccrualStatus.SKIPPED_ZERO);
        // October is still running: never accrued early
        assertThat(repository.list(UUID.fromString(s.id()))).extracting(InterestRepository.Accrual::period)
                .containsExactlyInAnyOrder("2026-07", "2026-08", "2026-09");

        assertThat(balance(s)).isEqualByComparingTo("1503.59");
        assertThat(balance(SystemAccounts.INTEREST)).isEqualByComparingTo("-3.59"); // the bank paid it
        assertBooksBalance();
    }

    @Test
    void theInterestTransactionIsMadeBySystemWithBalancedEntriesAndAReadableDescription() throws Exception {
        Acc s = savings(tokenFor("alice@example.com"), "2026-08-01");
        depositOn(s, "1000.00", Instant.parse("2026-09-01T00:10:00Z"));
        service.accrueAllDue();

        var row = jdbc.queryForMap("select type, initiated_by, description, amount, source_account_id, "
                + "destination_account_id from transactions where type = 'INTEREST'");
        assertThat(row.get("initiated_by")).isNull();
        assertThat(row.get("description")).isEqualTo("Interest for September 2026");
        assertThat(row.get("source_account_id")).isEqualTo(SystemAccounts.INTEREST);
        assertThat(row.get("destination_account_id")).isEqualTo(UUID.fromString(s.id()));
        assertThat(jdbc.queryForObject("select sum(e.amount) from ledger_entries e join transactions t on t.id = e.transaction_id "
                + "where t.type = 'INTEREST'", BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("select transaction_id is not null from interest_accruals where period = '2026-09'",
                Boolean.class)).isTrue();
    }

    @Test
    void aDepositInTheLastMinuteOfAMonthCountsForThatLastDayOnly() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc late = savings(alice, "2026-08-01");
        Acc early = savings(tokenFor("bob@example.com"), "2026-08-01");
        depositOn(late, "10000.00", Instant.parse("2026-08-31T23:59:00Z"));   // on August 31 UTC: 1 day of 31
        depositOn(early, "10000.00", Instant.parse("2026-09-01T00:00:00Z"));  // on September 1 UTC: none of August

        service.accrueAllDue();

        // August for "late": 10000 / 31 = 322.5806 average, x 0.0028709 = 0.93
        assertThat(accrual(late, "2026-08").amount()).isEqualByComparingTo("0.93");
        assertThat(accrual(early, "2026-08").status()).isEqualTo(AccrualStatus.SKIPPED_ZERO);
        // September for "early": 10000 all month x 0.0028709 = 28.71
        assertThat(accrual(early, "2026-09").amount()).isEqualByComparingTo("28.71");
    }

    @Test
    void anAccountOnlyEarnsFromTheMonthItWasOpened() throws Exception {
        Acc s = savings(tokenFor("alice@example.com"), "2026-09-20");
        depositOn(s, "3000.00", Instant.parse("2026-09-25T00:00:00Z")); // 6 of 30 days: 3000 x 6 / 30 = 600 average

        service.accrueAllDue();

        assertThat(repository.list(UUID.fromString(s.id()))).hasSize(1); // no rows for July or August
        assertThat(accrual(s, "2026-09").averageBalance()).isEqualByComparingTo("600.00");
        assertThat(accrual(s, "2026-09").amount()).isEqualByComparingTo("1.72"); // 600 x 0.0028709 = 1.7225
    }

    // ---- who earns --------------------------------------------------------------------------------------------

    @Test
    void onlyActiveSavingsAccountsEarnAndNoOtherAccountIsTouched() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc frozen = savings(alice, "2026-09-01");
        Acc tiny = savings(tokenFor("bob@example.com"), "2026-09-01");
        Acc checking = open(tokenFor("carol@example.com"), "CHECKING");
        depositOn(frozen, "5000.00", Instant.parse("2026-09-01T00:10:00Z"));
        depositOn(tiny, "0.50", Instant.parse("2026-09-01T00:10:00Z"));
        depositOn(checking, "5000.00", Instant.parse("2026-09-01T00:10:00Z"));
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", frozen.id());

        service.accrueAllDue();

        assertThat(accrual(frozen, "2026-09").status()).isEqualTo(AccrualStatus.SKIPPED_INACTIVE);
        assertThat(balance(frozen)).isEqualByComparingTo("5000.00");
        assertThat(accrual(tiny, "2026-09").status()).isEqualTo(AccrualStatus.SKIPPED_ZERO); // rounds to 0.00
        assertThat(balance(tiny)).isEqualByComparingTo("0.50");
        assertThat(repository.list(UUID.fromString(checking.id()))).isEmpty();
        assertThat(balance(checking)).isEqualByComparingTo("5000.00");
        assertThat(interestTransactions()).isZero();
    }

    // ---- exactly once ------------------------------------------------------------------------------------------

    @Test
    void runningAgainCreditsNothingButALaterMonthIsPickedUp() throws Exception {
        Acc s = savings(tokenFor("alice@example.com"), "2026-09-01");
        depositOn(s, "1000.00", Instant.parse("2026-09-01T00:10:00Z"));
        service.accrueAllDue();
        BigDecimal afterFirstRun = balance(s);

        InterestService.RunSummary second = service.accrueAllDue();

        assertThat(second).isEqualTo(new InterestService.RunSummary(false, 0, 0, 0));
        assertThat(balance(s)).isEqualByComparingTo(afterFirstRun);
        assertThat(interestTransactions()).isEqualTo(1);

        mutableClock.set(Instant.parse("2026-11-01T00:30:00Z")); // October is complete now
        InterestService.RunSummary third = service.accrueAllDue();

        assertThat(third.credited()).isEqualTo(1);
        assertThat(accrual(s, "2026-10").status()).isEqualTo(AccrualStatus.CREDITED);
        assertThat(interestTransactions()).isEqualTo(2);
        assertBooksBalance();
    }

    @Test
    void severalThreadsAccruingTheSameMonthCreditItExactlyOnce() throws Exception {
        Acc s = savings(tokenFor("alice@example.com"), "2026-09-01");
        depositOn(s, "2000.00", Instant.parse("2026-09-01T00:10:00Z"));
        UUID account = UUID.fromString(s.id());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AccrualStatus>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.accrueMonth(account, YearMonth.of(2026, 9));
            }));
        }
        start.countDown();
        long credited = 0;
        for (Future<AccrualStatus> result : results) {
            if (result.get(60, TimeUnit.SECONDS) == AccrualStatus.CREDITED) {
                credited++;
            }
        }
        pool.shutdown();

        assertThat(credited).isEqualTo(1);
        assertThat(interestTransactions()).isEqualTo(1);
        assertThat(repository.list(account)).hasSize(1);
        assertThat(balance(account)).isEqualByComparingTo("2005.74"); // 2000 + round(2000 x 0.0028709) = 5.74
        assertBooksBalance();
    }

    @Test
    void aSecondInstanceSkipsTheRunWhileAnotherHoldsTheLock() throws Exception {
        Acc s = savings(tokenFor("alice@example.com"), "2026-09-01");
        depositOn(s, "1000.00", Instant.parse("2026-09-01T00:10:00Z"));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        Future<?> holder = other.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("select pg_advisory_xact_lock(" + InterestService.ADVISORY_LOCK_KEY + ")");
            holding.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        InterestService.RunSummary blocked = service.accrueAllDue();
        release.countDown();
        holder.get(30, TimeUnit.SECONDS);
        other.shutdown();

        assertThat(blocked.skippedByLock()).isTrue();
        assertThat(interestTransactions()).isZero();
        assertThat(service.accrueAllDue().credited()).isEqualTo(1); // once the lock is free the work gets done
    }

    // ---- failure isolation -------------------------------------------------------------------------------------

    @Test
    void oneFailingAccountDoesNotStopTheOthers() throws Exception {
        Acc broken = savings(tokenFor("alice@example.com"), "2026-08-01");
        Acc healthy = savings(tokenFor("bob@example.com"), "2026-08-01");
        depositOn(broken, "1000.00", Instant.parse("2026-08-01T00:10:00Z"));
        depositOn(healthy, "1000.00", Instant.parse("2026-08-01T00:10:00Z"));
        doThrow(new IllegalStateException("boom")).when(ledger)
                .post(any(TransactionType.class), any(), contains(broken.id()), any(), any(), any(), any());

        InterestService.RunSummary summary = service.accrueAllDue();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.credited()).isEqualTo(2); // the healthy account: August and September
        assertThat(repository.list(UUID.fromString(broken.id()))).isEmpty(); // nothing half-written
        assertThat(balance(broken)).isEqualByComparingTo("1000.00");
        assertThat(repository.list(UUID.fromString(healthy.id()))).hasSize(2);
        assertBooksBalance();
    }
}
