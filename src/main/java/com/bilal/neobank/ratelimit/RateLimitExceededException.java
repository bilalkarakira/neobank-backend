package com.bilal.neobank.ratelimit;

import java.time.Duration;

/** Too many attempts. Mapped to 429 with a Retry-After header in GlobalExceptionHandler. */
public class RateLimitExceededException extends RuntimeException {

    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String TOO_MANY_LOGIN_ATTEMPTS = "TOO_MANY_LOGIN_ATTEMPTS";

    private final String code;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String code, Duration retryAfter) {
        super("Too many attempts. Please try again later.");
        this.code = code;
        this.retryAfterSeconds = Math.max(1, retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0));
    }

    public String getCode() {
        return code;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
