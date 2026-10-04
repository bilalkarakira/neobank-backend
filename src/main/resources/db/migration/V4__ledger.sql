-- The bank's own funding account has no owner and may go negative (it represents money handed out).
ALTER TABLE accounts ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE accounts DROP CONSTRAINT accounts_balance_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_balance_check CHECK (balance >= 0 OR type = 'SYSTEM');
ALTER TABLE accounts ADD CONSTRAINT accounts_owner_check CHECK (type = 'SYSTEM' OR user_id IS NOT NULL);

INSERT INTO accounts (id, user_id, iban, type, currency, status, nickname, balance, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM-FUNDING', 'SYSTEM', 'USD', 'ACTIVE',
        'Bank funding', 0, now());

CREATE TABLE transactions (
    id              UUID         PRIMARY KEY,
    type            VARCHAR(20)  NOT NULL,
    initiated_by    UUID         NOT NULL REFERENCES users (id),
    idempotency_key VARCHAR(64)  NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    description     VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_transactions_idempotency UNIQUE (initiated_by, idempotency_key)
);

-- One row per account touched by a transaction. Credit is positive, debit negative,
-- so the entries of every transaction sum to zero.
CREATE TABLE ledger_entries (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID           NOT NULL REFERENCES transactions (id),
    account_id     UUID           NOT NULL REFERENCES accounts (id),
    amount         NUMERIC(19, 4) NOT NULL CHECK (amount <> 0),
    balance_after  NUMERIC(19, 4) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id);
CREATE INDEX idx_ledger_entries_transaction ON ledger_entries (transaction_id);

-- History is never rewritten: mistakes are corrected with new entries.
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
