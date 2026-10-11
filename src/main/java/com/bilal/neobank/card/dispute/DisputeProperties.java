package com.bilal.neobank.card.dispute;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Settings of card disputes. {@code window} is how long the shop has to answer a chargeback the
 * network accepted: when it has passed without a representment the dispute is WON and the customer's credit is final. The
 * demo stack sets it to seconds; the default is two weeks.
 */
@ConfigurationProperties("app.cards.disputes")
public record DisputeProperties(@DefaultValue("14d") Duration window) {

    /** A payment can be disputed for this many days after it cleared (the network enforces the same limit). */
    public static final int MAX_AGE_DAYS = 120;

    @Configuration
    @EnableConfigurationProperties(DisputeProperties.class)
    static class Registration {
    }
}
