package com.bilal.neobank.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes expired counters and failure records every hour. Switch off with app.security.rate-limit.scheduler-enabled. */
@Component
@ConditionalOnProperty(name = "app.security.rate-limit.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitScheduler {

    private static final Logger log = LoggerFactory.getLogger(RateLimitScheduler.class);

    private final RateLimiter limiter;
    private final LoginLockout lockout;

    public RateLimitScheduler(RateLimiter limiter, LoginLockout lockout) {
        this.limiter = limiter;
        this.lockout = lockout;
    }

    @Scheduled(cron = "0 40 * * * *", zone = "UTC")
    public void purge() {
        log.info("Rate-limit housekeeping: {} counters and {} failure records removed", limiter.purge(), lockout.purge());
    }
}
