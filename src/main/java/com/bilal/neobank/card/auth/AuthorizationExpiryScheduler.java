package com.bilal.neobank.card.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link AuthorizationMaintenance#expireOldHolds()} once a day. */
@Component
@ConditionalOnProperty(name = "app.cards.expiry-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class AuthorizationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationExpiryScheduler.class);

    private final AuthorizationMaintenance maintenance;

    public AuthorizationExpiryScheduler(AuthorizationMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "UTC")
    public void expire() {
        log.info("Card hold housekeeping: {} expired holds marked", maintenance.expireOldHolds());
    }
}
