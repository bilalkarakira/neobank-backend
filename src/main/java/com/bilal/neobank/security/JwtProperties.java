package com.bilal.neobank.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.security.jwt")
public record JwtProperties(String secret, Duration ttl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.security.jwt.secret must be at least 32 bytes");
        }
    }
}
