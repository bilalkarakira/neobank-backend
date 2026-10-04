-- Fixed-window request counters ("at most N of X per window per key"). One row per (key, window); the window start
-- is the current time rounded down to the window length, so every app instance computes the same row and the upsert
-- in RateLimiter makes the count exact even under parallel requests.
CREATE TABLE rate_limits (
    bucket_key   VARCHAR(120) NOT NULL,
    window_start TIMESTAMPTZ  NOT NULL,
    hits         INT          NOT NULL,
    PRIMARY KEY (bucket_key, window_start)
);
CREATE INDEX idx_rate_limits_window ON rate_limits (window_start);

-- Failed logins per email, whether or not the email belongs to a customer: an unknown address locks exactly like a
-- known one, so lockouts cannot be used to find out who has an account. Only a hash of the address is kept.
CREATE TABLE login_failures (
    email_hash       CHAR(64)    PRIMARY KEY,
    failures         INT         NOT NULL,
    first_failure_at TIMESTAMPTZ NOT NULL,
    locked_until     TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL
);
