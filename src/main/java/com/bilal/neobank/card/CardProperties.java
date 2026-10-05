package com.bilal.neobank.card;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Card issuing settings. {@code panHmacKey} keys the hash of PANs and PINs: without it a stolen table cannot be used to
 * test guesses. The shipped default is for development only.
 */
@ConfigurationProperties("app.cards")
public record CardProperties(String panHmacKey, int maxPerCustomer, BigDecimal defaultDailyLimit,
        BigDecimal maxDailyLimit, BigDecimal perTransactionCap, int velocityMax, Duration velocityWindow,
        Duration holdTtl, int maxPinFailures, List<String> blockedMcc, boolean authorizationsEnabled,
        Listener listener) {

    public record Listener(boolean enabled, int port, int maxConnections, Duration idleTimeout) {
    }

    @Configuration
    @EnableConfigurationProperties(CardProperties.class)
    static class Registration {
    }
}
