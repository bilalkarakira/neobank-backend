package com.bilal.neobank.interest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the interest job daily. The first run after a month ends accrues that month, and any run catches up on months
 * that were missed (for example because the app was down on the 1st). Switch off with app.interest.scheduler-enabled.
 */
@Component
@ConditionalOnProperty(name = "app.interest.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class InterestScheduler {

    private final InterestService service;

    public InterestScheduler(InterestService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.interest.cron}", zone = "UTC")
    public void run() {
        service.accrueAllDue();
    }
}
