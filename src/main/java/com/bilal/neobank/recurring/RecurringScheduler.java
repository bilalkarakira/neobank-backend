package com.bilal.neobank.recurring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs due standing orders on a timer (hourly by default). A missed run is simply picked up by the next pass.
 * Switch off with app.recurring.scheduler-enabled.
 */
@Component
@ConditionalOnProperty(name = "app.recurring.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class RecurringScheduler {

    private final RecurringExecutor executor;

    public RecurringScheduler(RecurringExecutor executor) {
        this.executor = executor;
    }

    @Scheduled(cron = "${app.recurring.cron}", zone = "UTC")
    public void run() {
        executor.runAllDue();
    }
}
