package com.bilal.neobank.transfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/** Business limits for transfers. The daily limit counts money sent to OTHER customers (UTC calendar day). */
@ConfigurationProperties("app.transfers")
public record TransferProperties(BigDecimal dailyLimit) {

    @Configuration
    @EnableConfigurationProperties(TransferProperties.class)
    static class Registration {
    }
}
