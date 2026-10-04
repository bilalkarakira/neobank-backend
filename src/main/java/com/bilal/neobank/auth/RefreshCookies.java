package com.bilal.neobank.auth;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Builds the refresh cookie. HttpOnly (scripts cannot read it, so an XSS bug cannot steal it), SameSite=Strict
 * (never sent on cross-site requests), and scoped to /api/auth so it is not attached to ordinary API calls.
 */
@Component
public class RefreshCookies {

    public static final String NAME = "neobank_refresh";
    static final String PATH = "/api/auth";

    private final RefreshProperties properties;
    private final Clock clock;

    public RefreshCookies(RefreshProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String set(RefreshTokenService.Issued issued) {
        Duration maxAge = Duration.between(clock.instant(), issued.expiresAt());
        return base(issued.rawToken()).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build().toString();
    }

    public String clear() {
        return base("").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(properties.cookieSecure()).sameSite("Strict")
                .path(PATH);
    }
}
