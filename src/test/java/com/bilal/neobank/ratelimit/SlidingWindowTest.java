package com.bilal.neobank.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The arithmetic alone: no database, no clock. */
class SlidingWindowTest {

    private static final double W = 60;

    @Test
    void theEstimateWeighsThePreviousWindowByHowMuchOfItIsStillInsideTheLastWindowLength() {
        assertThat(SlidingWindow.estimate(5, 10, 0, W)).isEqualTo(15);      // just started: all of last window counts
        assertThat(SlidingWindow.estimate(5, 10, 30, W)).isEqualTo(10);     // half way: half of it
        assertThat(SlidingWindow.estimate(5, 10, 60, W)).isEqualTo(5);      // end: none of it
        assertThat(SlidingWindow.estimate(5, 10, 75, W)).isEqualTo(5);      // never negative past the end
        assertThat(SlidingWindow.estimate(0, 0, 12, W)).isZero();
    }

    @Test
    void noWaitIsNeededWhenTheEstimateIsAlreadyLowEnough() {
        assertThat(SlidingWindow.secondsUntilAtMost(1, 2, 10, W, 10)).isZero();
        assertThat(SlidingWindow.secondsUntilAtMost(0, 0, 0, W, 0)).isZero();
    }

    @Test
    void whenNothingBelongsToThePreviousWindowTheWaitRunsToTheRolloverAndThenDecays() {
        // 20 of 20 used in this window (target 19 = limit - 1 for "this request plus the rest must fit"):
        // nothing changes until the window ends (60 s), then 20 x (1 - t/60) <= 19 needs 3 more seconds
        assertThat(SlidingWindow.secondsUntilAtMost(20, 0, 0, W, 19)).isCloseTo(63, within(1e-9));
        assertThat(SlidingWindow.secondsUntilAtMost(20, 0, 25, W, 19)).isCloseTo(38, within(1e-9)); // 25 s already gone
    }

    @Test
    void insideTheWindowThePreviousSharesDecaysLinearly() {
        // current 2, previous 10: 2 + 10 x (1 - t/60) <= 7  ->  t = 30
        assertThat(SlidingWindow.secondsUntilAtMost(2, 10, 0, W, 7)).isCloseTo(30, within(1e-9));
        // the same situation 15 s later needs 15 s more
        assertThat(SlidingWindow.secondsUntilAtMost(2, 10, 15, W, 7)).isCloseTo(15, within(1e-9));
        // nothing in the current window, only the previous one decaying: 10 x (1 - t/60) <= 5 -> t = 30
        assertThat(SlidingWindow.secondsUntilAtMost(0, 10, 0, W, 5)).isCloseTo(30, within(1e-9));
    }

    @Test
    void whenTheCurrentCountAloneIsTooHighEvenAFullyDecayedPreviousWindowDoesNotHelpUntilTheRollover() {
        // current 8 > target 7: waiting out the previous window cannot get below 7; wait for the rollover,
        // after which the 8 become the previous window: 8 x (1 - t/60) <= 7 -> 7.5 s
        assertThat(SlidingWindow.secondsUntilAtMost(8, 10, 0, W, 7)).isCloseTo(60 + 7.5, within(1e-9));
    }

    /** The estimate t seconds from now if no further events happen (rollover included). */
    private static double estimateAfter(double current, double previous, double elapsed, double window, double t) {
        double untilRollover = window - elapsed;
        return t <= untilRollover ? SlidingWindow.estimate(current, previous, elapsed + t, window)
                : SlidingWindow.estimate(0, current, t - untilRollover, window);
    }

    @Test
    void theWaitIsExactlyEnoughAndNoLongerThanNeededForManyRandomSituations() {
        Random random = new Random(20261005);
        int checked = 0;
        for (int i = 0; i < 5000; i++) {
            double window = 10 + random.nextInt(3600);
            double current = random.nextInt(40);
            double previous = random.nextInt(40);
            double elapsed = random.nextDouble() * window;
            double target = random.nextInt(40);
            double wait = SlidingWindow.secondsUntilAtMost(current, previous, elapsed, window, target);

            assertThat(wait).isGreaterThanOrEqualTo(0);
            // sufficient: at the computed moment the estimate is at or below the target
            assertThat(estimateAfter(current, previous, elapsed, window, wait))
                    .as("case %d sufficient: c=%s p=%s e=%s W=%s T=%s", i, current, previous, elapsed, window, target)
                    .isLessThanOrEqualTo(target + 1e-7);
            // minimal: a little earlier it is still above (unless no waiting was needed at all)
            if (wait > 1e-6) {
                assertThat(estimateAfter(current, previous, elapsed, window, wait - 1e-3))
                        .as("case %d minimal: c=%s p=%s e=%s W=%s T=%s", i, current, previous, elapsed, window, target)
                        .isGreaterThan(target);
                checked++;
            }
        }
        assertThat(checked).as("the sample really exercised waiting").isGreaterThan(1000);
    }
}
