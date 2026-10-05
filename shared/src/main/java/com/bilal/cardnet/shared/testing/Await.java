package com.bilal.cardnet.shared.testing;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** For tests only: wait for something that happens on another thread, without fixed sleeps. */
public final class Await {

    private Await() {
    }

    /** Polls every 20 ms until the condition holds. @throws AssertionError after the timeout */
    public static void until(String what, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what);
            }
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("timed out after " + timeout.toMillis() + " ms waiting for: " + what);
        }
    }

    public static void until(String what, BooleanSupplier condition) {
        until(what, Duration.ofSeconds(10), condition);
    }
}
