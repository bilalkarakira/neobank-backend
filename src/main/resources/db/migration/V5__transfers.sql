-- A transaction now says directly who paid whom (the ledger entries remain the source of truth).
ALTER TABLE transactions ADD COLUMN source_account_id      UUID REFERENCES accounts (id);
ALTER TABLE transactions ADD COLUMN destination_account_id UUID REFERENCES accounts (id);
ALTER TABLE transactions ADD COLUMN amount                 NUMERIC(19, 4);

UPDATE transactions t SET
    source_account_id      = (SELECT e.account_id FROM ledger_entries e WHERE e.transaction_id = t.id AND e.amount < 0),
    destination_account_id = (SELECT e.account_id FROM ledger_entries e WHERE e.transaction_id = t.id AND e.amount > 0),
    amount                 = (SELECT e.amount     FROM ledger_entries e WHERE e.transaction_id = t.id AND e.amount > 0);

ALTER TABLE transactions ALTER COLUMN source_account_id      SET NOT NULL;
ALTER TABLE transactions ALTER COLUMN destination_account_id SET NOT NULL;
ALTER TABLE transactions ALTER COLUMN amount                 SET NOT NULL;
ALTER TABLE transactions ADD CONSTRAINT transactions_amount_check CHECK (amount > 0);

CREATE INDEX idx_transactions_initiator_created ON transactions (initiated_by, created_at);
