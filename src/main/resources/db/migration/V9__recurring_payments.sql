-- Standing orders: send this amount to this IBAN every week or month. The schedule is fully described by
-- (start_date, frequency, occurrence): the next run date is start_date + occurrence weeks/months, so a monthly order
-- started on the 31st lands on the last day of shorter months and returns to the 31st afterwards.
CREATE TABLE recurring_payments (
    id                   UUID           PRIMARY KEY,
    user_id              UUID           NOT NULL REFERENCES users (id),
    source_account_id    UUID           NOT NULL REFERENCES accounts (id),
    destination_iban     VARCHAR(40)    NOT NULL,
    amount               NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    description          VARCHAR(100),
    category_id          UUID REFERENCES categories (id),
    frequency            VARCHAR(10)    NOT NULL,           -- WEEKLY | MONTHLY
    start_date           DATE           NOT NULL,
    end_date             DATE,
    occurrence           INT            NOT NULL DEFAULT 0, -- how many scheduled dates have been consumed
    next_run_date        DATE,                              -- null once COMPLETED or CANCELLED
    status               VARCHAR(12)    NOT NULL,           -- ACTIVE | PAUSED | COMPLETED | CANCELLED
    auto_paused          BOOLEAN        NOT NULL DEFAULT FALSE,
    consecutive_failures INT            NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_recurring_user ON recurring_payments (user_id);
CREATE INDEX idx_recurring_due ON recurring_payments (next_run_date) WHERE status = 'ACTIVE';

-- One row per scheduled date that was attempted. The primary key is the last guard against running a date twice.
CREATE TABLE recurring_runs (
    payment_id      UUID         NOT NULL REFERENCES recurring_payments (id),
    run_date        DATE         NOT NULL,
    status          VARCHAR(10)  NOT NULL,                  -- SUCCESS | FAILED
    failure_code    VARCHAR(40),
    failure_message VARCHAR(200),
    transaction_id  UUID REFERENCES transactions (id),
    executed_at     TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (payment_id, run_date)
);
