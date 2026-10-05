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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sliding-window counters in Postgres (see {@link SlidingWindow} for the arithmetic). Events are counted in fixed
 * windows, one row per (key, window), and the allowance is judged on the estimate over "the last W seconds".
 *
 * Every write runs in its OWN transaction (REQUIRES_NEW): an attempt must stay counted even when the request that made
 * it fails and rolls back, otherwise the failures this exists to count would erase themselves. The upsert makes
 * parallel requests count exactly, on one instance or several.
 *
 * A request that is refused does NOT stay counted: hammering a limit must not extend the block it is already in.
 */
@Service
public class RateLimiter {

    /** A key whose recent events are at or above its limit, and when it will be allowed again (if nothing more happens). */
    public record Blocked(String key, double estimate, Instant until) {
    }

    private static final double STRICT_MARGIN_SECONDS = 0.001;

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    public RateLimiter(JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Counts one event and refuses it when the last window's allowance, this event included, would be exceeded. */
    public void check(String bucket, Window window) {
        double length = window.window().toSeconds();
        Instant start = windowStart(window.window());
        double elapsed = elapsed(start);
        Counts counts = incrementAndRead(bucket, start, start.minus(window.window()));
        double estimate = SlidingWindow.estimate(counts.current(), counts.previous(), elapsed, length);
        if (estimate > window.limit() + SlidingWindow.EPSILON) {
            decrement(bucket, start); // a refused request is not an event: it must not lengthen its own block
            double without = counts.current() - 1;
            throw exceeded(SlidingWindow.secondsUntilAtMost(without, counts.previous(), elapsed, length, window.limit() - 1));
        }
    }

    /** Refuses when the recent events already reach the limit, without counting a new one. */
    public void requireBelow(String bucket, Window window) {
        double length = window.window().toSeconds();
        Instant start = windowStart(window.window());
        double elapsed = elapsed(start);
        Counts counts = read(bucket, start, start.minus(window.window()));
        double estimate = SlidingWindow.estimate(counts.current(), counts.previous(), elapsed, length);
        if (estimate >= window.limit() - SlidingWindow.EPSILON) {
            // blocked until the estimate is STRICTLY below the limit. At the exact moment it reaches the limit it is
            // still blocked, so the advertised wait carries a millisecond of margin: waiting it always works
            throw exceeded(STRICT_MARGIN_SECONDS + SlidingWindow.secondsUntilAtMost(counts.current(), counts.previous(),
                    elapsed, length, window.limit() - 2 * SlidingWindow.EPSILON));
        }
    }

    /** Counts one event without judging it (used to record misses). Returns the current window's count. */
    public int hit(String bucket, Duration window) {
        Instant start = windowStart(window);
        return incrementAndRead(bucket, start, start.minus(window)).current();
    }

    /** The estimated number of events in the last window-length, as of now. */
    public double estimate(String bucket, Duration window) {
        Instant start = windowStart(window);
        Counts counts = read(bucket, start, start.minus(window));
        return SlidingWindow.estimate(counts.current(), counts.previous(), elapsed(start), window.toSeconds());
    }

    /**
     * Every key starting with {@code prefix} whose estimate is at or above the limit right now, with the moment it will
     * be allowed again. Used by the admin view of probing blocks.
     */
    public List<Blocked> blocked(String prefix, Window window) {
        Instant start = windowStart(window.window());
        Instant previousStart = start.minus(window.window());
        double length = window.window().toSeconds();
        double elapsed = elapsed(start);
        Map<String, double[]> perKey = new LinkedHashMap<>(); // key -> {current, previous}
        jdbc.sql("select bucket_key, window_start, hits from rate_limits where bucket_key like :prefix "
                + "and window_start in (:current, :previous)")
                .param("prefix", prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                .param("current", at(start)).param("previous", at(previousStart))
                .query((rs, i) -> {
                    double[] pair = perKey.computeIfAbsent(rs.getString("bucket_key"), k -> new double[2]);
                    boolean isCurrent = rs.getObject("window_start", OffsetDateTime.class).toInstant().equals(start);
                    pair[isCurrent ? 0 : 1] = rs.getInt("hits");
                    return null;
                }).list();
        List<Blocked> blocked = new ArrayList<>();
        perKey.forEach((key, pair) -> {
            double estimate = SlidingWindow.estimate(pair[0], pair[1], elapsed, length);
            if (estimate >= window.limit() - SlidingWindow.EPSILON) {
                double seconds = SlidingWindow.secondsUntilAtMost(pair[0], pair[1], elapsed, length,
                        window.limit() - 2 * SlidingWindow.EPSILON);
                // whole seconds, rounded up, with the same margin requireBelow advertises
                blocked.add(new Blocked(key, estimate,
                        clock.instant().plusSeconds((long) Math.ceil(seconds + STRICT_MARGIN_SECONDS))));
            }
        });
        blocked.sort((a, b) -> Double.compare(b.estimate(), a.estimate()));
        return blocked;
    }

    /** Housekeeping: counters of windows that ended more than a day ago are of no use any more. */
    public int purge() {
        return ownTransaction.execute(status -> jdbc.sql("delete from rate_limits where window_start < :cutoff")
                .param("cutoff", at(clock.instant().minus(Duration.ofDays(1)))).update());
    }

    /** The start of the current window: now, rounded down to the window length, the same instant on every instance. */
    public Instant windowStart(Duration window) {
        long length = window.toSeconds();
        long now = clock.instant().getEpochSecond();
        return Instant.ofEpochSecond(now - Math.floorMod(now, length));
    }

    private record Counts(int current, int previous) {
    }

    private Counts incrementAndRead(String bucket, Instant start, Instant previousStart) {
        return ownTransaction.execute(status -> jdbc.sql("""
                with bumped as (
                    insert into rate_limits (bucket_key, window_start, hits) values (:key, :start, 1)
                    on conflict (bucket_key, window_start) do update set hits = rate_limits.hits + 1
                    returning hits
                )
                select (select hits from bumped) as current_hits,
                       coalesce((select hits from rate_limits where bucket_key = :key and window_start = :previous), 0) as previous_hits
                """).param("key", bucket).param("start", at(start)).param("previous", at(previousStart))
                .query((rs, i) -> new Counts(rs.getInt("current_hits"), rs.getInt("previous_hits"))).single());
    }

    private Counts read(String bucket, Instant start, Instant previousStart) {
        return jdbc.sql("""
                select coalesce((select hits from rate_limits where bucket_key = :key and window_start = :start), 0) as current_hits,
                       coalesce((select hits from rate_limits where bucket_key = :key and window_start = :previous), 0) as previous_hits
                """).param("key", bucket).param("start", at(start)).param("previous", at(previousStart))
                .query((rs, i) -> new Counts(rs.getInt("current_hits"), rs.getInt("previous_hits"))).single();
    }

    private void decrement(String bucket, Instant start) {
        ownTransaction.executeWithoutResult(status -> jdbc.sql(
                "update rate_limits set hits = greatest(hits - 1, 0) where bucket_key = :key and window_start = :start")
                .param("key", bucket).param("start", at(start)).update());
    }

    private double elapsed(Instant start) {
        return Duration.between(start, clock.instant()).toNanos() / 1e9;
    }

    private static RateLimitExceededException exceeded(double seconds) {
        // rounded to the millisecond first, so a wait that is exactly 63 s does not become 64 through floating-point dust
        return new RateLimitExceededException(RateLimitExceededException.RATE_LIMITED,
                Duration.ofMillis(Math.round(Math.max(0, seconds) * 1000)));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
