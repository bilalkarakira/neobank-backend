package com.bilal.neobank.card.clearing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Settings of the clearing file exchange with the card network (see docs/SPEC-P4-clearing.md). {@code dir} is the folder shared
 * with the network and the acquirer (this bank reads {@code issuer-in/} and writes {@code issuer-out/}); {@code fileSecret} is
 * the text the file key is made from and must be the same in every process that reads or writes clearing files. The shipped
 * default is for development only.
 */
@ConfigurationProperties("app.cards.clearing")
public record ClearingProperties(boolean enabled, @DefaultValue("./data/clearing") String dir,
        @DefaultValue("999900") String issuerId, @DefaultValue("NET") String networkName,
        @DefaultValue("neobank-dev-clearing-key-change-me") String fileSecret, @DefaultValue("5s") Duration poll) {

    @Configuration
    @EnableConfigurationProperties(ClearingProperties.class)
    static class Registration {
    }
}
