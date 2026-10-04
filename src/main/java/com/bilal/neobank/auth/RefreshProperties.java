package com.bilal.neobank.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * @param ttl          how long one refresh token stays valid (renewed by every rotation)
 * @param maxLifetime  the longest a single login can last in total
 * @param reuseGrace   a token that was rotated this recently is refused WITHOUT revoking the login: two browser tabs
 *                     refreshing at once legitimately present the same token
 * @param cookieSecure send the cookie over HTTPS only; must be true in production
 * @param schedulerEnabled purge expired tokens daily
 */
@ConfigurationProperties("app.security.refresh")
public record RefreshProperties(Duration ttl, Duration maxLifetime, Duration reuseGrace, boolean cookieSecure,
        boolean schedulerEnabled) {

    public RefreshProperties {
        if (ttl == null || maxLifetime == null || reuseGrace == null) {
            throw new IllegalArgumentException("app.security.refresh ttl, max-lifetime and reuse-grace are required");
        }
    }

    @Configuration
    @EnableConfigurationProperties(RefreshProperties.class)
    static class Registration {
    }
}
