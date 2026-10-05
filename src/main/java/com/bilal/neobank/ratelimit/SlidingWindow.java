package com.bilal.neobank.ratelimit;

/**
 * The arithmetic of a sliding-window counter, kept free of the database and the clock so it can be tested exhaustively.
 *
 * Events are counted in fixed windows of W seconds. The number of events in "the last W seconds" is estimated as
 *
 *     estimate = current window's count + previous window's count x (1 - elapsed / W)
 *
 * where {@code elapsed} is how far into the current window we are. The previous window's events are assumed to be spread
 * evenly, so the share that has slid out of the last W seconds is dropped gradually instead of all at once at the
 * boundary. That is what stops a burst right before a boundary from being followed by a fresh full allowance right after
 * it (the weakness of plain fixed windows: up to twice the limit in a short time).
 */
final class SlidingWindow {

    /** Doubles are compared with a hair of tolerance so "exactly at the limit" does not flip on rounding noise. */
    static final double EPSILON = 1e-9;

    private SlidingWindow() {
    }

    static double estimate(double current, double previous, double elapsed, double window) {
        return current + previous * Math.max(0.0, 1.0 - elapsed / window);
    }

    /**
     * How many seconds until the estimate has fallen to {@code target} or below, assuming no further events happen.
     * Zero when it already has.
     *
     * Two stretches are possible. Inside the current window only the previous window's share decays, so the estimate
     * falls linearly. If even a fully decayed previous window leaves the current count above the target, nothing helps
     * until the window rolls over: then the whole current count becomes the "previous" window and decays from there.
     */
    static double secondsUntilAtMost(double current, double previous, double elapsed, double window, double target) {
        if (estimate(current, previous, elapsed, window) <= target) {
            return 0.0;
        }
        if (previous > 0 && target >= current) {
            // inside this window: current + previous x (1 - (elapsed + t) / W) = target
            return Math.max(0.0, window * (1.0 - (target - current) / previous) - elapsed);
        }
        // after the rollover (at t0 = W - elapsed): current x (1 - t' / W) = target
        double untilRollover = window - elapsed;
        if (current <= 0) {
            return untilRollover;
        }
        return untilRollover + Math.max(0.0, window * (1.0 - target / current));
    }
}
