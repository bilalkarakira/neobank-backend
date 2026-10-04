package com.bilal.neobank.recurring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @param maxOpen     active + paused orders per user
 * @param maxFailures consecutive failed runs before an order pauses itself
 * @param maxCatchUp  most scheduled dates one order may run in a single job pass (after downtime)
 */
@ConfigurationProperties("app.recurring")
public record RecurringProperties(boolean schedulerEnabled, String cron, int maxOpen, int maxFailures,
        int maxCatchUp) {

    @Configuration
    @EnableConfigurationProperties(RecurringProperties.class)
    static class Registration {
    }
}
