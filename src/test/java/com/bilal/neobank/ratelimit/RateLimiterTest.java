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
import static org.assertj.core.api.Assertions.within;

/** The sliding-window limiter against the real database, with a movable clock (START is exactly 12:00:00). */
class RateLimiterTest extends RateLimitTestBase {

    private static final Window THREE_PER_MINUTE = new Window(3, Duration.ofMinutes(1));

    @Autowired
    RateLimiter limiter;

    @Autowired
    PlatformTransactionManager transactionManager;

    private void fill(String key, int times) {
        for (int i = 0; i < times; i++) {
            limiter.check(key, THREE_PER_MINUTE);
        }
    }

    private boolean allowed(String key) {
        try {
            limiter.check(key, THREE_PER_MINUTE);
            return true;
        } catch (RateLimitExceededException e) {
            return false;
        }
    }

    private long retryAfter(String key) {
        try {
            limiter.check(key, THREE_PER_MINUTE);
        } catch (RateLimitExceededException e) {
            return e.getRetryAfterSeconds();
        }
        throw new AssertionError("expected the limit to be exceeded");
    }

    private void at(String time) {
        clock.set(Instant.parse("2026-10-04T" + time + "Z"));
    }

    // ---- the basics -------------------------------------------------------------------------------------------

    @Test
    void allowsExactlyTheLimitAndRefusesTheNext() {
        fill("k", 3);
        assertThatThrownBy(() -> limiter.check("k", THREE_PER_MINUTE)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void keysAreIndependent() {
        fill("a", 3);
        assertThat(allowed("b")).isTrue();
        assertThat(allowed("a")).isFalse();
    }

    @Test
    void aRefusedRequestIsNotCountedSoHammeringDoesNotLengthenTheBlock() {
        fill("k", 3);
        for (int i = 0; i < 50; i++) {
            assertThat(allowed("k")).isFalse();
        }

        assertThat(limiter.estimate("k", Duration.ofMinutes(1))).isCloseTo(3.0, within(1e-9)); // not 53
        assertThat(retryAfter("k")).isEqualTo(retryAfterAtTheStart());                          // and the wait did not grow
    }

    private long retryAfterAtTheStart() {
        return 60 + 20; // 3 used at 12:00:00: roll over (60 s), then 3 x (1 - t/60) <= 2 needs 20 s
    }

    // ---- the point of sliding windows -------------------------------------------------------------------------

    @Test
    void aBurstJustBeforeAWindowBoundaryIsNotFollowedByAFreshAllowanceJustAfterIt() {
        at("12:00:59");
        fill("k", 3);

        at("12:01:00"); // a fixed window would have forgotten everything one second later
        assertThat(allowed("k")).isFalse();
        at("12:01:10");
        assertThat(allowed("k")).isFalse();
        at("12:01:19");
        assertThat(allowed("k")).isFalse();
    }

    @Test
    void theAllowanceComesBackGraduallyAsTheEarlierRequestsSlideOut() {
        at("12:00:00");
        fill("k", 3);

        at("12:01:19"); // 3 x (1 - 19/60) = 2.05, plus this one: just over 3
        assertThat(allowed("k")).isFalse();
        at("12:01:20"); // 3 x (1 - 20/60) = 2, plus this one: exactly 3
        assertThat(allowed("k")).isTrue();
        assertThat(allowed("k")).isFalse();      // the next one has to wait again
        at("12:01:40"); // previous now weighs 1, current holds 1: 1 + 1 + 1 = 3
        assertThat(allowed("k")).isTrue();
    }

    @Test
    void aFullWindowOfQuietRestoresTheFullAllowance() {
        at("12:00:00");
        fill("k", 3);

        at("12:02:00"); // two whole windows later nothing remains of it
        fill("k", 3);
        assertThat(allowed("k")).isFalse();
    }

    // ---- Retry-After ------------------------------------------------------------------------------------------

    @Test
    void retryAfterIsTheExactWaitWaitingThatLongWorksAndOneSecondLessDoesNot() {
        at("12:00:20");
        fill("k", 3);
        long wait = retryAfter("k");
        assertThat(wait).isEqualTo(60); // 40 s to the rollover, then 3 x (1 - t/60) <= 2 needs 20 s

        Instant refused = clock.instant();
        clock.set(refused.plusSeconds(wait - 1));
        assertThat(allowed("k")).as("one second early").isFalse();
        clock.set(refused.plusSeconds(wait));
        assertThat(allowed("k")).as("after exactly the advertised wait").isTrue();
    }

    @Test
    void retryAfterIsNeverZeroAndNeverNegative() {
        at("12:00:59");
        fill("k", 3);
        assertThat(retryAfter("k")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theAdvertisedWaitWorksForManyDifferentMomentsInTheWindow() {
        for (int second = 0; second < 60; second += 7) {
            String key = "k" + second;
            clock.set(Instant.parse("2026-10-04T12:00:00Z").plusSeconds(second));
            fill(key, 3);
            long wait = retryAfter(key);
            Instant refused = clock.instant();

            clock.set(refused.plusSeconds(wait - 1));
            assertThat(allowed(key)).as("second %d: early", second).isFalse();
            clock.set(refused.plusSeconds(wait));
            assertThat(allowed(key)).as("second %d: on time", second).isTrue();
        }
    }

    // ---- requireBelow and misses ------------------------------------------------------------------------------

    private static final Window TEN_MISSES = new Window(10, Duration.ofMinutes(10));

    @Test
    void requireBelowOnlyLooksAndTenMissesBlockUntilTheyHaveSlidOutGradually() {
        for (int i = 0; i < 9; i++) {
            limiter.hit("m", TEN_MISSES.window());
            limiter.requireBelow("m", TEN_MISSES); // 9 misses: still fine, and looking costs nothing
        }
        limiter.hit("m", TEN_MISSES.window());
        assertThat(limiter.estimate("m", TEN_MISSES.window())).isEqualTo(10.0);

        assertThatThrownBy(() -> limiter.requireBelow("m", TEN_MISSES)).isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfterSeconds()).isEqualTo(601));

        at("12:10:00"); // the window rolled over, but the ten misses still count in full: not a fresh start
        assertThatThrownBy(() -> limiter.requireBelow("m", TEN_MISSES)).isInstanceOf(RateLimitExceededException.class);
        at("12:10:01"); // now the oldest have begun to slide out
        limiter.requireBelow("m", TEN_MISSES);
    }

    @Test
    void theAdvertisedWaitForMissesAlsoWorks() {
        for (int i = 0; i < 10; i++) {
            limiter.hit("m", TEN_MISSES.window());
        }
        long wait = ((RateLimitExceededException) catchThrowable(() -> limiter.requireBelow("m", TEN_MISSES))).getRetryAfterSeconds();

        clock.set(START.plusSeconds(wait - 1));
        assertThatThrownBy(() -> limiter.requireBelow("m", TEN_MISSES)).isInstanceOf(RateLimitExceededException.class);
        clock.set(START.plusSeconds(wait));
        limiter.requireBelow("m", TEN_MISSES);
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected an exception");
    }

    @Test
    void blockedListsOnlyKeysAtTheLimitAndSaysWhenEachWillBeAllowedAgain() {
        for (int i = 0; i < 10; i++) {
            limiter.hit("probe:blocked", TEN_MISSES.window());
        }
        for (int i = 0; i < 9; i++) {
            limiter.hit("probe:almost", TEN_MISSES.window());
        }
        limiter.hit("other:ten", TEN_MISSES.window()); // a different prefix

        List<RateLimiter.Blocked> blocked = limiter.blocked("probe:", TEN_MISSES);

        assertThat(blocked).hasSize(1);
        assertThat(blocked.get(0).key()).isEqualTo("probe:blocked");
        assertThat(blocked.get(0).estimate()).isEqualTo(10.0);
        assertThat(blocked.get(0).until()).isEqualTo(START.plusSeconds(601));
    }

    @Test
    void blockedCountsTheSlidingPreviousWindowToo() {
        for (int i = 0; i < 10; i++) {
            limiter.hit("probe:u", TEN_MISSES.window());
        }
        at("12:10:00"); // new window, the misses are in the previous one at full weight
        assertThat(limiter.blocked("probe:", TEN_MISSES)).hasSize(1);
        at("12:15:00"); // half a window later they count half
        assertThat(limiter.blocked("probe:", TEN_MISSES)).isEmpty();
    }

    // ---- storage and concurrency ------------------------------------------------------------------------------

    @Test
    void anAttemptStaysCountedWhenTheCallersTransactionRollsBack() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            limiter.check("k", THREE_PER_MINUTE);
            throw new IllegalStateException("the request fails");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(limiter.estimate("k", Duration.ofMinutes(1))).isEqualTo(1.0);
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
        // the 20 refused requests were taken back out again: only the 10 real ones remain counted
        assertThat(limiter.estimate("shared", Duration.ofMinutes(1))).isEqualTo(10.0);
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
