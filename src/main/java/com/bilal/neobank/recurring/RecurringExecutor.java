package com.bilal.neobank.recurring;

import com.bilal.neobank.account.AccountNotActiveException;
import com.bilal.neobank.account.AccountNotFoundException;
import com.bilal.neobank.category.CategoryNotFoundException;
import com.bilal.neobank.ledger.IdempotencyKeyReusedException;
import com.bilal.neobank.ledger.InsufficientFundsException;
import com.bilal.neobank.recurring.RecurringRepository.Row;
import com.bilal.neobank.transfer.TransferExceptions;
import com.bilal.neobank.transfer.TransferService;
import com.bilal.neobank.transfer.dto.TransferRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Runs the standing orders that are due.
 *
 * Money safety comes from the idempotency key {@code rec-<order id>-<run date>}: the transfer goes through the normal
 * TransferService, so even if the job crashes after the money moved, or two instances race, that date can only ever
 * move money once. An advisory lock keeps whole runs from overlapping, and the order row lock plus the primary key of
 * recurring_runs keep the bookkeeping exactly-once too.
 *
 * The transfer must NOT share a transaction with the bookkeeping: a business failure (no funds) would mark it
 * rollback-only and we could not record the failure. So each step is three separate transactions: (1) read the due
 * date, (2) the transfer, (3) record the outcome and advance the schedule.
 */
@Service
public class RecurringExecutor {

    private static final Logger log = LoggerFactory.getLogger(RecurringExecutor.class);

    /** Arbitrary but fixed: the key of the Postgres advisory lock that serialises whole runs. */
    public static final long ADVISORY_LOCK_KEY = 8_103_002L;

    /** @param skippedByLock true when another instance was already running */
    public record RunSummary(boolean skippedByLock, int succeeded, int failed) {
    }

    private enum Step { NOTHING_DUE, SUCCEEDED, FAILED }

    private final RecurringRepository repository;
    private final TransferService transferService;
    private final RecurringProperties properties;
    private final Clock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate runTransaction;
    private final TransactionTemplate stepTransaction;

    public RecurringExecutor(RecurringRepository repository, TransferService transferService,
            RecurringProperties properties, Clock clock, JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transferService = transferService;
        this.properties = properties;
        this.clock = clock;
        this.jdbc = jdbc;
        this.runTransaction = new TransactionTemplate(transactionManager);
        this.stepTransaction = new TransactionTemplate(transactionManager);
        this.stepTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public RunSummary runAllDue() {
        return runTransaction.execute(status -> {
            Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:key)").param("key", ADVISORY_LOCK_KEY)
                    .query(Boolean.class).single();
            if (!Boolean.TRUE.equals(locked)) {
                log.info("Recurring run skipped: another instance holds the lock");
                return new RunSummary(true, 0, 0);
            }
            int succeeded = 0;
            int failed = 0;
            for (UUID id : repository.dueIds(today())) {
                try {
                    for (int i = 0; i < properties.maxCatchUp(); i++) {
                        Step step = step(id);
                        if (step == Step.NOTHING_DUE) {
                            break;
                        }
                        if (step == Step.SUCCEEDED) {
                            succeeded++;
                        } else {
                            failed++;
                        }
                    }
                } catch (RuntimeException e) {
                    // unexpected (not a business failure): leave the order untouched, it is retried on the next pass
                    log.error("Recurring payment {} could not be processed: {}", id, e.toString());
                }
            }
            log.info("Recurring run finished: {} succeeded, {} failed", succeeded, failed);
            return new RunSummary(false, succeeded, failed);
        });
    }

    private Step step(UUID id) {
        LocalDate today = today();
        Row due = stepTransaction.execute(status -> repository.findForUpdate(id)
                .filter(r -> r.status() == RecurringStatus.ACTIVE && r.nextRunDate() != null
                        && !r.nextRunDate().isAfter(today))
                .orElse(null));
        if (due == null) {
            return Step.NOTHING_DUE;
        }
        LocalDate runDate = due.nextRunDate();
        String key = "rec-" + id + "-" + runDate.format(DateTimeFormatter.BASIC_ISO_DATE);

        UUID transactionId = null;
        String failureCode = null;
        String failureMessage = null;
        try {
            // our caller holds the run's advisory-lock transaction; the transfer needs one of its own, which a
            // business failure rolls back cleanly without touching the bookkeeping below
            transactionId = stepTransaction.execute(status -> transferService.transfer(due.userId(), key,
                    new TransferRequest(due.sourceAccountId(), due.destinationIban(), due.amount(),
                            due.description(), due.categoryId())).response().transactionId());
        } catch (RuntimeException e) {
            failureCode = failureCode(e);
            if (failureCode == null) {
                throw e;
            }
            failureMessage = truncate(e.getMessage());
        }

        UUID finalTransactionId = transactionId;
        String code = failureCode;
        String message = failureMessage;
        Boolean recorded;
        try {
            recorded = stepTransaction.execute(status -> record(id, runDate, finalTransactionId, code, message));
        } catch (DataIntegrityViolationException e) {
            recorded = false; // the primary key says this date was already recorded
        }
        if (!Boolean.TRUE.equals(recorded)) {
            return Step.NOTHING_DUE; // someone else handled or changed this order meanwhile; do not touch it again
        }
        return failureCode == null ? Step.SUCCEEDED : Step.FAILED;
    }

    /** Records the outcome and moves the schedule to the next date, unless the order changed under us. */
    private boolean record(UUID id, LocalDate runDate, UUID transactionId, String failureCode, String failureMessage) {
        Row row = repository.findForUpdate(id).orElse(null);
        if (row == null || row.status() != RecurringStatus.ACTIVE || !runDate.equals(row.nextRunDate())) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        repository.insertRun(id, runDate, failureCode == null ? "SUCCESS" : "FAILED", failureCode, failureMessage,
                transactionId, now);

        int failures = failureCode == null ? 0 : row.consecutiveFailures() + 1;
        int occurrence = row.occurrence() + 1;
        LocalDate next = row.frequency().occurrence(row.startDate(), occurrence);
        if (row.endDate() != null && next.isAfter(row.endDate())) {
            repository.updateState(id, occurrence, null, RecurringStatus.COMPLETED, false, failures);
        } else if (failures >= properties.maxFailures()) {
            repository.updateState(id, occurrence, next, RecurringStatus.PAUSED, true, failures);
        } else {
            repository.updateState(id, occurrence, next, RecurringStatus.ACTIVE, false, failures);
        }
        return true;
    }

    /** Business failures are recorded and the date is consumed; anything else returns null (retry later). */
    static String failureCode(RuntimeException e) {
        return switch (e) {
            case InsufficientFundsException x -> "INSUFFICIENT_FUNDS";
            case TransferExceptions.DailyLimitExceededException x -> "DAILY_LIMIT_EXCEEDED";
            case AccountNotActiveException x -> "ACCOUNT_NOT_ACTIVE";
            case AccountNotFoundException x -> "ACCOUNT_NOT_FOUND";
            case TransferExceptions.RecipientUnavailableException x -> "RECIPIENT_UNAVAILABLE";
            case TransferExceptions.RecipientNotFoundException x -> "RECIPIENT_NOT_FOUND";
            case TransferExceptions.SameAccountException x -> "SAME_ACCOUNT";
            case TransferExceptions.InvalidIbanException x -> "INVALID_IBAN";
            case CategoryNotFoundException x -> "CATEGORY_NOT_FOUND";
            case IdempotencyKeyReusedException x -> "KEY_REUSED";
            default -> null;
        };
    }

    private static String truncate(String message) {
        return message == null ? null : message.length() <= 200 ? message : message.substring(0, 200);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
