package com.bilal.neobank.ratelimit;

import com.bilal.neobank.ledger.Fingerprint;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Locks an email address after too many wrong passwords, whether or not the address belongs to a customer, so the
 * lock reveals nothing about who has an account. While locked, even the correct password is refused: the whole point
 * is that a guesser learns nothing from the attempts. The lock ends by itself.
 *
 * Known trade-off: anyone who knows an email can lock that customer out for the lock duration. That is the price of
 * stopping password guessing without CAPTCHAs or device recognition; the per-address lock is short for that reason.
 */
@Service
public class LoginLockout {

    private record State(int failures, OffsetDateTime firstFailureAt, OffsetDateTime lockedUntil) {
    }

    private final JdbcClient jdbc;
    private final RateLimitProperties.Login settings;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    public LoginLockout(JdbcClient jdbc, RateLimitProperties properties, PlatformTransactionManager transactionManager,
            Clock clock) {
        this.jdbc = jdbc;
        this.settings = properties.login();
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        // a failed attempt must stay counted even if the surrounding request rolls back
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Throws (429) while the address is locked. Call BEFORE checking the password. */
    public void assertNotLocked(String normalizedEmail) {
        OffsetDateTime now = now();
        read(hash(normalizedEmail)).map(State::lockedUntil).filter(until -> until != null && until.isAfter(now))
                .ifPresent(until -> {
                    throw new RateLimitExceededException(RateLimitExceededException.TOO_MANY_LOGIN_ATTEMPTS,
                            Duration.between(now, until));
                });
    }

    /** How long a lock lasts. */
    public Duration lockDuration() {
        return settings.lockDuration();
    }

    /** @return true when THIS failure is the one that locked the address (exactly once per lock) */
    public boolean recordFailure(String normalizedEmail) {
        String key = hash(normalizedEmail);
        return Boolean.TRUE.equals(ownTransaction.execute(status -> {
            OffsetDateTime now = now();
            // make sure the row exists, then lock it so parallel failures are counted one after the other
            jdbc.sql("insert into login_failures (email_hash, failures, first_failure_at, updated_at) "
                    + "values (:key, 0, :now, :now) on conflict (email_hash) do nothing")
                    .param("key", key).param("now", now).update();
            State state = jdbc.sql("select failures, first_failure_at, locked_until from login_failures "
                    + "where email_hash = :key for update").param("key", key)
                    .query((rs, i) -> new State(rs.getInt("failures"), rs.getObject("first_failure_at", OffsetDateTime.class),
                            rs.getObject("locked_until", OffsetDateTime.class))).single();

            boolean lockEnded = state.lockedUntil() != null && !state.lockedUntil().isAfter(now);
            boolean windowEnded = state.firstFailureAt().plus(settings.failureWindow()).isBefore(now);
            int failures = (lockEnded || windowEnded || state.failures() == 0) ? 1 : state.failures() + 1;
            OffsetDateTime first = (lockEnded || windowEnded || state.failures() == 0) ? now : state.firstFailureAt();
            OffsetDateTime lockedUntil = failures >= settings.maxFailures() ? now.plus(settings.lockDuration())
                    : (lockEnded ? null : state.lockedUntil());

            jdbc.sql("update login_failures set failures = :failures, first_failure_at = :first, "
                    + "locked_until = :locked, updated_at = :now where email_hash = :key")
                    .param("failures", failures).param("first", first).param("locked", lockedUntil)
                    .param("now", now).param("key", key).update();
            boolean wasLocked = state.lockedUntil() != null && state.lockedUntil().isAfter(now);
            return failures >= settings.maxFailures() && !wasLocked;
        }));
    }

    /** A correct password wipes the slate. */
    public void recordSuccess(String normalizedEmail) {
        ownTransaction.executeWithoutResult(status -> jdbc.sql("delete from login_failures where email_hash = :key")
                .param("key", hash(normalizedEmail)).update());
    }

    /** Housekeeping: forgets old failure records that are no longer locked. */
    public int purge() {
        OffsetDateTime cutoff = now().minus(Duration.ofDays(1));
        return ownTransaction.execute(status -> jdbc.sql(
                "delete from login_failures where updated_at < :cutoff and (locked_until is null or locked_until < :cutoff)")
                .param("cutoff", cutoff).update());
    }

    private Optional<State> read(String key) {
        return jdbc.sql("select failures, first_failure_at, locked_until from login_failures where email_hash = :key")
                .param("key", key)
                .query((rs, i) -> new State(rs.getInt("failures"), rs.getObject("first_failure_at", OffsetDateTime.class),
                        rs.getObject("locked_until", OffsetDateTime.class))).optional();
    }

    private static String hash(String normalizedEmail) {
        return Fingerprint.sha256(normalizedEmail);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
