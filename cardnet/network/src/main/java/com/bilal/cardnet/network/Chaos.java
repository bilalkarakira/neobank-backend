package com.bilal.cardnet.network;

import com.bilal.cardnet.shared.Json;

import java.util.Map;

/**
 * Switches that make the failures of the demo repeatable: a dead issuer, a slow issuer (it really decided, its answer is
 * just held back) and a slow network. In memory only and all off at start.
 */
public final class Chaos {

    public static final long MAX_DELAY_MS = 60_000;

    private volatile boolean issuerDown;
    private volatile long issuerResponseDelayMs;
    private volatile long acquirerResponseDelayMs;

    public boolean issuerDown() {
        return issuerDown;
    }

    public long issuerResponseDelayMs() {
        return issuerResponseDelayMs;
    }

    public long acquirerResponseDelayMs() {
        return acquirerResponseDelayMs;
    }

    public void issuerDown(boolean down) {
        this.issuerDown = down;
    }

    public void issuerResponseDelayMs(long millis) {
        this.issuerResponseDelayMs = check(millis);
    }

    public void acquirerResponseDelayMs(long millis) {
        this.acquirerResponseDelayMs = check(millis);
    }

    /** Applies a flat object such as {"issuerDown":true,"issuerResponseDelayMs":4000}; unknown names and bad values are refused. */
    public synchronized void apply(Map<String, Object> settings) {
        Chaos copy = new Chaos(); // all or nothing
        copy.issuerDown = issuerDown;
        copy.issuerResponseDelayMs = issuerResponseDelayMs;
        copy.acquirerResponseDelayMs = acquirerResponseDelayMs;
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            switch (entry.getKey()) {
                case "issuerDown" -> copy.issuerDown = bool(entry.getValue());
                case "issuerResponseDelayMs" -> copy.issuerResponseDelayMs = check(number(entry.getValue()));
                case "acquirerResponseDelayMs" -> copy.acquirerResponseDelayMs = check(number(entry.getValue()));
                default -> throw new IllegalArgumentException("unknown setting " + entry.getKey());
            }
        }
        issuerDown = copy.issuerDown;
        issuerResponseDelayMs = copy.issuerResponseDelayMs;
        acquirerResponseDelayMs = copy.acquirerResponseDelayMs;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = Json.object();
        map.put("issuerDown", issuerDown);
        map.put("issuerResponseDelayMs", issuerResponseDelayMs);
        map.put("acquirerResponseDelayMs", acquirerResponseDelayMs);
        return map;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("expected true or false");
    }

    private static long number(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        throw new IllegalArgumentException("expected a whole number of milliseconds");
    }

    private static long check(long millis) {
        if (millis < 0 || millis > MAX_DELAY_MS) {
            throw new IllegalArgumentException("a delay must be between 0 and " + MAX_DELAY_MS + " ms");
        }
        return millis;
    }
}
