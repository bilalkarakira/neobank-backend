package com.bilal.neobank.ratelimit;

import com.bilal.neobank.ratelimit.RateLimitProperties.Window;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Fixed-window counters in Postgres. Every write runs in its OWN transaction (REQUIRES_NEW): an attempt must stay
 * counted even when the request that made it fails and rolls back, otherwise the failures this exists to count would
 * erase themselves. The upsert makes parallel requests count exactly, on one instance or several.
 */
@Service
public class RateLimiter {

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    public RateLimiter(JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Counts one event and refuses it when the window's limit is already used up. */
    public void check(String bucket, Window window) {
        int hits = hit(bucket, window.window());
        if (hits > window.limit()) {
            throw exceeded(window.window());
        }
    }

    /** Refuses when the window already holds {@code limit} events, without counting a new one. */
    public void requireBelow(String bucket, Window window) {
        if (current(bucket, window.window()) >= window.limit()) {
            throw exceeded(window.window());
        }
    }

    /** Counts one event and returns how many the current window now holds. */
    public int hit(String bucket, Duration window) {
        Instant start = windowStart(window);
        Integer hits = ownTransaction.execute(status -> jdbc.sql("""
                insert into rate_limits (bucket_key, window_start, hits) values (:key, :start, 1)
                on conflict (bucket_key, window_start) do update set hits = rate_limits.hits + 1
                returning hits
                """).param("key", bucket).param("start", at(start)).query(Integer.class).single());
        return hits;
    }

    /** Events counted so far in the current window. */
    public int current(String bucket, Duration window) {
        return jdbc.sql("select coalesce(max(hits), 0) from rate_limits where bucket_key = :key and window_start = :start")
                .param("key", bucket).param("start", at(windowStart(window))).query(Integer.class).single();
    }

    /** Housekeeping: counters of windows that ended more than a day ago are of no use any more. */
    public int purge() {
        return ownTransaction.execute(status -> jdbc.sql("delete from rate_limits where window_start < :cutoff")
                .param("cutoff", at(clock.instant().minus(Duration.ofDays(1)))).update());
    }

    private RateLimitExceededException exceeded(Duration window) {
        Instant end = windowStart(window).plus(window);
        return new RateLimitExceededException(RateLimitExceededException.RATE_LIMITED,
                Duration.between(clock.instant(), end));
    }

    /** Now, rounded down to the window length: the same instant on every instance. */
    public Instant windowStart(Duration window) {
        long length = window.toSeconds();
        long now = clock.instant().getEpochSecond();
        return Instant.ofEpochSecond(now - Math.floorMod(now, length));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
