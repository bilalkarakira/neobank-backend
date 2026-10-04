package com.bilal.neobank.history;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties("app.history")
public record HistoryProperties(int exportMaxRows) {

    @Configuration
    @EnableConfigurationProperties(HistoryProperties.class)
    static class Registration {
    }
}
