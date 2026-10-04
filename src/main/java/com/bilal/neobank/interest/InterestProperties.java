package com.bilal.neobank.interest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.math.RoundingMode;

@ConfigurationProperties("app.interest")
public record InterestProperties(BigDecimal apy, boolean schedulerEnabled, String cron) {

    /** "3.50": the yearly rate as a percentage string, for display. */
    public String apyPercent() {
        return apy.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @Configuration
    @EnableConfigurationProperties(InterestProperties.class)
    static class Registration {
    }
}
