package com.bilal.neobank.ratelimit;

import com.bilal.neobank.ratelimit.RateLimitProperties.Window;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest extends RateLimitTestBase {

    private static final Window THREE_PER_MINUTE = new Window(3, Duration.ofMinutes(1));

    @Autowired
    RateLimiter limiter;

    @Autowired
    PlatformTransactionManager transactionManager;

    private long retryAfterOf(Runnable action) {
        return catchRetryAfter(action).getRetryAfterSeconds();
    }

    private static RateLimitExceededException catchRetryAfter(Runnable action) {
        try {
            action.run();
        } catch (RateLimitExceededException e) {
            return e;
        }
        throw new AssertionError("expected the limit to be exceeded");
    }

    @Test
    void allowsExactlyTheLimitAndRefusesTheNext() {
        limiter.check("k", THREE_PER_MINUTE);
        limiter.check("k", THREE_PER_MINUTE);
        limiter.check("k", THREE_PER_MINUTE);

        assertThatThrownBy(() -> limiter.check("k", THREE_PER_MINUTE)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.check("a", THREE_PER_MINUTE);
        }
        limiter.check("b", THREE_PER_MINUTE); // a different key has its own allowance
        assertThatThrownBy(() -> limiter.check("a", THREE_PER_MINUTE)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void retryAfterIsTheTimeLeftInTheWindow() {
        clock.set(Instant.parse("2026-10-04T12:00:20Z")); // 20 s into the 12:00 minute
        for (int i = 0; i < 3; i++) {
            limiter.check("k", THREE_PER_MINUTE);
        }
        assertThat(retryAfterOf(() -> limiter.check("k", THREE_PER_MINUTE))).isEqualTo(40);

        clock.set(Instant.parse("2026-10-04T12:00:59.500Z"));
        assertThat(retryAfterOf(() -> limiter.check("k", THREE_PER_MINUTE))).isEqualTo(1); // never 0: "retry now" would fail
    }

    @Test
    void aNewWindowStartsFromZero() {
        for (int i = 0; i < 3; i++) {
            limiter.check("k", THREE_PER_MINUTE);
        }
        after(Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) {
            limiter.check("k", THREE_PER_MINUTE);
        }
        assertThatThrownBy(() -> limiter.check("k", THREE_PER_MINUTE)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void requireBelowDoesNotCountItself() {
        limiter.hit("k", Duration.ofMinutes(1));
        limiter.hit("k", Duration.ofMinutes(1));
        for (int i = 0; i < 10; i++) {
            limiter.requireBelow("k", THREE_PER_MINUTE); // only looking: still 2 of 3
        }
        assertThat(limiter.current("k", Duration.ofMinutes(1))).isEqualTo(2);
        limiter.hit("k", Duration.ofMinutes(1));
        assertThatThrownBy(() -> limiter.requireBelow("k", THREE_PER_MINUTE)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void anAttemptStaysCountedWhenTheCallersTransactionRollsBack() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            limiter.hit("k", Duration.ofMinutes(1));
            throw new IllegalStateException("the request fails");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(limiter.current("k", Duration.ofMinutes(1))).isEqualTo(1);
    }

    @Test
    void parallelRequestsAreCountedExactlySoOnlyTheLimitGetsThrough() throws Exception {
        int threads = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Window ten = new Window(10, Duration.ofMinutes(1));
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    limiter.check("shared", ten);
                    return true;
                } catch (RateLimitExceededException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int allowed = 0;
        for (Future<Boolean> result : results) {
            if (result.get(60, TimeUnit.SECONDS)) {
                allowed++;
            }
        }
        pool.shutdown();

        assertThat(allowed).isEqualTo(10);
        assertThat(limiter.current("shared", Duration.ofMinutes(1))).isEqualTo(30);
    }

    @Test
    void purgeRemovesOnlyWindowsThatEndedMoreThanADayAgo() {
        limiter.hit("old", Duration.ofMinutes(1));
        after(Duration.ofHours(23));
        limiter.hit("recent", Duration.ofMinutes(1));
        after(Duration.ofHours(2)); // "old" is now 25 h old, "recent" 2 h

        assertThat(limiter.purge()).isEqualTo(1);
        assertThat(jdbc.queryForList("select bucket_key from rate_limits", String.class)).containsExactly("recent");
    }
}
