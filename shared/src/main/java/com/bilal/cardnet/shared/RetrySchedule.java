package com.bilal.cardnet.shared;

import java.time.Duration;

/**
 * How long to wait before trying to deliver a reversal again: 1, 2, 4, 8, 16 seconds, then every 60 seconds. A scale
 * below 1 shrinks the waits (tests use it to run the real schedule in milliseconds).
 */
public final class RetrySchedule {

    private static final long[] SECONDS = { 1, 2, 4, 8, 16 };
    private static final long STEADY_SECONDS = 60;

    private final double scale;

    public RetrySchedule(double scale) {
        if (scale <= 0) {
            throw new IllegalArgumentException("scale must be positive");
        }
        this.scale = scale;
    }

    public static RetrySchedule standard() {
        return new RetrySchedule(1.0);
    }

    /** The wait after {@code attemptsDone} attempts have failed (1 after the first failure). */
    public Duration delayAfter(int attemptsDone) {
        long seconds = attemptsDone >= 1 && attemptsDone <= SECONDS.length ? SECONDS[attemptsDone - 1] : STEADY_SECONDS;
        return Duration.ofNanos((long) (seconds * 1_000_000_000L * scale));
    }
}
