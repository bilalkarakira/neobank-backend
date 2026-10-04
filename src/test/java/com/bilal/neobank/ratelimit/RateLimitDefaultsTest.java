package com.bilal.neobank.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limits that actually ship: the other tests lift them, so this is what stops a careless edit of
 * application.yaml from quietly switching protection off (a limit of 100000 would pass every other test).
 */
class RateLimitDefaultsTest {

    private RateLimitProperties shipped() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        var environment = new org.springframework.core.env.StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addFirst);
        return new Binder(ConfigurationPropertySources.get(environment))
                .bind("app.security.rate-limit", RateLimitProperties.class).get();
    }

    @Test
    void loginLocksAfterFiveFailuresForFifteenMinutes() throws Exception {
        var login = shipped().login();
        assertThat(login.maxFailures()).isEqualTo(5);
        assertThat(login.failureWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(login.lockDuration()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void addressAndUserLimitsAreTheDocumentedOnes() throws Exception {
        RateLimitProperties p = shipped();
        assertThat(p.loginPerIp()).isEqualTo(new RateLimitProperties.Window(20, Duration.ofMinutes(1)));
        assertThat(p.refreshPerIp()).isEqualTo(new RateLimitProperties.Window(30, Duration.ofMinutes(1)));
        assertThat(p.registerPerIp()).isEqualTo(new RateLimitProperties.Window(10, Duration.ofHours(1)));
        assertThat(p.lookupPerUser()).isEqualTo(new RateLimitProperties.Window(20, Duration.ofMinutes(1)));
        assertThat(p.probeMisses()).isEqualTo(new RateLimitProperties.Window(10, Duration.ofMinutes(10)));
    }
}
