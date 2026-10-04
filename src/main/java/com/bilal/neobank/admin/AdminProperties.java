package com.bilal.neobank.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Optional first administrator, created on startup when BOTH values are set (env APP_ADMIN_EMAIL and
 * APP_ADMIN_PASSWORD). Nothing is created by default: a bank must never ship with a known admin password.
 */
@ConfigurationProperties("app.admin")
public record AdminProperties(String email, String password) {

    @Configuration
    @EnableConfigurationProperties(AdminProperties.class)
    static class Registration {
    }
}
