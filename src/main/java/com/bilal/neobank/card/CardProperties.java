package com.bilal.neobank.card;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
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

    /** {@code host}: the address the ISO 8583 listener binds to. Loopback by default; a container needs 0.0.0.0 (and must not publish the port). */
    public record Listener(boolean enabled, int port, int maxConnections, Duration idleTimeout, @DefaultValue("127.0.0.1") String host) {
    }

    @Configuration
    @EnableConfigurationProperties(CardProperties.class)
    static class Registration {
    }
}
