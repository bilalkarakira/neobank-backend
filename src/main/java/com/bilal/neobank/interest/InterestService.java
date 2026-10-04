package com.bilal.neobank.interest;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.analytics.AnalyticsRepository;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.LedgerTransaction;
import com.bilal.neobank.ledger.Posting;
import com.bilal.neobank.ledger.SystemAccounts;
import com.bilal.neobank.ledger.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Pays monthly interest on savings accounts.
 *
 * Correctness comes from three layers: an advisory lock so two app instances do not run at once, a row lock plus an
 * "already processed?" check per (account, month), and the primary key of interest_accruals as the final guard. Every
 * (account, month) is handled in its own database transaction, so one failing account never blocks the others.
 */
@Service
public class InterestService {

    private static final Logger log = LoggerFactory.getLogger(InterestService.class);

    /** Arbitrary but fixed: the key of the Postgres advisory lock that serialises whole runs. */
    public static final long ADVISORY_LOCK_KEY = 8_103_001L;

    /** @param skippedByLock true when another instance was already running */
    public record RunSummary(boolean skippedByLock, int credited, int skipped, int failed) {
    }

    private final AccountRepository accountRepository;
    private final InterestRepository repository;
    private final AnalyticsRepository analyticsRepository;
    private final LedgerService ledgerService;
    private final InterestProperties properties;
    private final Clock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate runTransaction;
    private final TransactionTemplate monthTransaction;

    public InterestService(AccountRepository accountRepository, InterestRepository repository,
            AnalyticsRepository analyticsRepository, LedgerService ledgerService, InterestProperties properties,
            Clock clock, JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.repository = repository;
        this.analyticsRepository = analyticsRepository;
        this.ledgerService = ledgerService;
        this.properties = properties;
        this.clock = clock;
        this.jdbc = jdbc;
        this.runTransaction = new TransactionTemplate(transactionManager);
        // each (account, month) commits or rolls back on its own, independent of the run's lock transaction
        this.monthTransaction = new TransactionTemplate(transactionManager);
        this.monthTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Accrues every completed month that has not been accrued yet, for every savings account. */
    public RunSummary accrueAllDue() {
        return runTransaction.execute(status -> {
            // held until this transaction ends; a second instance gets false and simply skips this run
            Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:key)").param("key", ADVISORY_LOCK_KEY)
                    .query(Boolean.class).single();
            if (!Boolean.TRUE.equals(locked)) {
                log.info("Interest run skipped: another instance holds the lock");
                return new RunSummary(true, 0, 0, 0);
            }
            return processAll();
        });
    }

    private RunSummary processAll() {
        YearMonth lastCompleted = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1);
        int credited = 0;
        int skipped = 0;
        int failed = 0;

        for (Account account : accountRepository.findByTypeOrderByCreatedAtAsc(AccountType.SAVINGS)) {
            Set<String> done = repository.processedPeriods(account.getId());
            YearMonth first = YearMonth.from(account.getCreatedAt().atOffset(ZoneOffset.UTC));
            for (YearMonth period = first; !period.isAfter(lastCompleted); period = period.plusMonths(1)) {
                if (done.contains(period.toString())) {
                    continue;
                }
                try {
                    AccrualStatus result = accrueMonth(account.getId(), period);
                    if (result == AccrualStatus.CREDITED) {
                        credited++;
                    } else if (result != null) {
                        skipped++;
                    }
                } catch (RuntimeException e) {
                    // keep going with the other accounts; stop THIS account so months stay in order
                    failed++;
                    log.error("Interest for account {} month {} failed: {}", account.getId(), period, e.toString());
                    break;
                }
            }
        }
        log.info("Interest run finished: {} credited, {} skipped, {} failed", credited, skipped, failed);
        return new RunSummary(false, credited, skipped, failed);
    }

    /**
     * Accrues one month of one savings account, in its own transaction.
     *
     * @return what was recorded, or null when the month had already been processed (by anyone)
     */
    public AccrualStatus accrueMonth(UUID accountId, YearMonth period) {
        try {
            return monthTransaction.execute(status -> accrueInTransaction(accountId, period));
        } catch (DataIntegrityViolationException e) {
            return null; // another run recorded this month first (primary key); our whole transaction rolled back
        }
    }

    private AccrualStatus accrueInTransaction(UUID accountId, YearMonth period) {
        // serialise with anything else touching this account, then re-check: a concurrent run may have just finished
        Account account = accountRepository.lockAllOrderedById(List.of(accountId)).stream().findFirst().orElseThrow();
        String key = period.toString();
        if (repository.exists(accountId, key)) {
            return null;
        }
        BigDecimal rate = InterestCalculator.monthlyRate(properties.apy());
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        if (account.getStatus() != AccountStatus.ACTIVE) {
            repository.insert(accountId, key, AccrualStatus.SKIPPED_INACTIVE, BigDecimal.ZERO, rate, BigDecimal.ZERO,
                    null, now);
            return AccrualStatus.SKIPPED_INACTIVE;
        }

        LocalDate first = period.atDay(1);
        List<BigDecimal> days = analyticsRepository.dailyBalances(List.of(accountId), first, period.atEndOfMonth())
                .stream().map(AnalyticsRepository.DailyBalance::balance).toList();
        BigDecimal average = InterestCalculator.averageBalance(days);
        BigDecimal amount = InterestCalculator.interest(average, rate);

        if (amount.signum() == 0) {
            repository.insert(accountId, key, AccrualStatus.SKIPPED_ZERO, average, rate, BigDecimal.ZERO, null, now);
            return AccrualStatus.SKIPPED_ZERO;
        }

        LedgerTransaction transaction = ledgerService.post(TransactionType.INTEREST, null,
                "interest-" + accountId + "-" + key, "interest", description(period),
                List.of(new Posting(SystemAccounts.INTEREST, amount.negate()), new Posting(accountId, amount)), null);
        repository.insert(accountId, key, AccrualStatus.CREDITED, average, rate, amount, transaction.getId(), now);
        return AccrualStatus.CREDITED;
    }

    /** "Interest for September 2026" (also used by the demo data generator). */
    public static String description(YearMonth period) {
        return "Interest for " + period.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + period.getYear();
    }
}
