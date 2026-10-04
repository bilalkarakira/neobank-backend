-- The bank's interest expense: savings interest is paid out of this system account (like the funding account,
-- it has no owner and may go negative).
INSERT INTO accounts (id, user_id, iban, type, currency, status, nickname, balance, created_at)
VALUES ('00000000-0000-0000-0000-000000000002', NULL, 'SYSTEM-INTEREST', 'SYSTEM', 'USD', 'ACTIVE',
        'Bank interest', 0, now());

-- Interest is made by the system, not by a user.
ALTER TABLE transactions ALTER COLUMN initiated_by DROP NOT NULL;

-- One row per (savings account, month): the audit trail of HOW each month's interest was computed, and the guard
-- that makes sure a month is processed exactly once.
CREATE TABLE interest_accruals (
    account_id      UUID           NOT NULL REFERENCES accounts (id),
    period          CHAR(7)        NOT NULL,                 -- '2026-09'
    status          VARCHAR(20)    NOT NULL,                 -- CREDITED | SKIPPED_ZERO | SKIPPED_INACTIVE
    average_balance NUMERIC(19, 4) NOT NULL,
    monthly_rate    NUMERIC(12, 10) NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    transaction_id  UUID REFERENCES transactions (id),
    processed_at    TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (account_id, period)
);
