package com.bilal.neobank.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes long-expired refresh tokens once a day. Switch off with app.security.refresh.scheduler-enabled. */
@Component
@ConditionalOnProperty(name = "app.security.refresh.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class RefreshTokenScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenScheduler.class);

    private final RefreshTokenService service;

    public RefreshTokenScheduler(RefreshTokenService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    public void purge() {
        log.info("Purged {} expired refresh tokens", service.purgeExpired());
    }
}
