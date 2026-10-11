package com.bilal.neobank.card.clearing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Settings of the clearing file exchange with the card network. {@code dir} is the folder shared
 * with the network and the acquirer (this bank reads {@code issuer-in/} and writes {@code issuer-out/}); {@code fileSecret} is
 * the text the file key is made from and must be the same in every process that reads or writes clearing files. The shipped
 * default is for development only. The two rates are the ones agreed with the network (interchange NeoBank earns, scheme fee it
 * pays, both as a fraction of each cleared amount, rounded half up to the cent per item): the settlement is checked against them.
 */
@ConfigurationProperties("app.cards.clearing")
public record ClearingProperties(boolean enabled, @DefaultValue("./data/clearing") String dir,
        @DefaultValue("999900") String issuerId, @DefaultValue("NET") String networkName,
        @DefaultValue("neobank-dev-clearing-key-change-me") String fileSecret, @DefaultValue("5s") Duration poll,
        @DefaultValue("0.0120") BigDecimal interchangeRate, @DefaultValue("0.0005") BigDecimal schemeFeeRate) {

    @Configuration
    @EnableConfigurationProperties(ClearingProperties.class)
    static class Registration {
    }
}
