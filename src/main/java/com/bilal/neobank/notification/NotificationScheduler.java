package com.bilal.neobank.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Deletes notices older than the retention period (app.notifications.retention, default 90 days), read or not, once a day. */
@Component
@ConditionalOnProperty(name = "app.notifications.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationService notifications;
    private final Duration retention;

    public NotificationScheduler(NotificationService notifications,
            @Value("${app.notifications.retention:90d}") Duration retention) {
        this.notifications = notifications;
        this.retention = retention;
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "UTC")
    public void purge() {
        log.info("Notification housekeeping: {} notices older than {} removed", notifications.purge(retention), retention);
    }
}
