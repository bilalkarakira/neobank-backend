package com.bilal.neobank.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Business limits: how many open accounts of each type one customer may hold. */
@ConfigurationProperties("app.accounts")
public record AccountProperties(int maxChecking, int maxSavings) {

    public int maxFor(AccountType type) {
        return type == AccountType.CHECKING ? maxChecking : maxSavings;
    }

    @Configuration
    @EnableConfigurationProperties(AccountProperties.class)
    static class Registration {
    }
}
