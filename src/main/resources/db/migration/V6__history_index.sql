-- "latest N entries of one account", the core history query, becomes an index scan instead of a sort.
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries (account_id, created_at DESC, id DESC);
