package com.bilal.neobank.ratelimit;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stops a signed-in user from using recipient lookup and transfers to discover which IBANs exist. Two limits:
 * lookups per minute (speed), and misses (unknown or invalid IBANs) per window (the actual sign of enumeration).
 * An honest typo costs one miss; ten in ten minutes is not a typo.
 */
@Component
public class RecipientProbeGuard {

    private final RateLimiter limiter;
    private final RateLimitProperties properties;

    public RecipientProbeGuard(RateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    public void beforeLookup(UUID userId) {
        limiter.check("lookup:" + userId, properties.lookupPerUser());
        limiter.requireBelow("probe:" + userId, properties.probeMisses());
    }

    public void beforeTransfer(UUID userId) {
        limiter.requireBelow("probe:" + userId, properties.probeMisses());
    }

    public void recordMiss(UUID userId) {
        limiter.hit("probe:" + userId, properties.probeMisses().window());
    }
}
