package com.bilal.neobank.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Abuse limits. Every value is a deliberate trade-off between stopping guessing and not locking out honest users.
 *
 * @param login           failed-password lockout per email address
 * @param loginPerIp      login attempts per client address (stops one machine from trying many emails)
 * @param refreshPerIp    refresh attempts per client address
 * @param registerPerIp   new accounts per client address
 * @param lookupPerUser   recipient lookups per signed-in user
 * @param probeMisses     lookups or transfers to an unknown/invalid IBAN per user: the signal of someone enumerating
 */
@ConfigurationProperties("app.security.rate-limit")
public record RateLimitProperties(Login login, Window loginPerIp, Window refreshPerIp, Window registerPerIp,
        Window lookupPerUser, Window probeMisses) {

    /** @param maxFailures failures within failureWindow that lock the address for lockDuration */
    public record Login(int maxFailures, Duration failureWindow, Duration lockDuration) {
    }

    /** At most {@code limit} events per {@code window}. */
    public record Window(int limit, Duration window) {
    }

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class Registration {
    }
}
