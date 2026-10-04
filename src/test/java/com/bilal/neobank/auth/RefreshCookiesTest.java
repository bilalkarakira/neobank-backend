package com.bilal.neobank.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshCookiesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);

    private RefreshCookies cookies(boolean secure) {
        return new RefreshCookies(new RefreshProperties(Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(10),
                secure, false), CLOCK);
    }

    @Test
    void productionSettingMarksTheCookieSecure() {
        String header = cookies(true).set(new RefreshTokenService.Issued("abc", CLOCK.instant().plusSeconds(3600)));
        assertThat(header).contains("Secure").contains("HttpOnly").contains("SameSite=Strict")
                .contains("Path=/api/auth").contains("Max-Age=3600");
    }

    @Test
    void theLifetimeFollowsTheTokenSoTheCookieAndTheDatabaseExpireTogether() {
        String header = cookies(false).set(new RefreshTokenService.Issued("abc", CLOCK.instant().plus(Duration.ofDays(2))));
        assertThat(header).contains("Max-Age=172800").doesNotContain("Secure");
    }

    @Test
    void anAlreadyExpiredTokenGivesAZeroLifetimeNotANegativeOne() {
        String header = cookies(false).set(new RefreshTokenService.Issued("abc", CLOCK.instant().minusSeconds(60)));
        assertThat(header).contains("Max-Age=0");
    }

    @Test
    void clearingEmptiesTheValueWithTheSameScopeSoTheBrowserActuallyReplacesTheCookie() {
        String header = cookies(true).clear();
        assertThat(header).contains("neobank_refresh=;").contains("Max-Age=0").contains("Path=/api/auth")
                .contains("HttpOnly").contains("Secure");
    }
}
