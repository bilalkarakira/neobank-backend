-- History asks "was this transaction made by a standing order?" for every row it shows. A transaction belongs to at
-- most one run, so the lookup is by transaction and the index is UNIQUE: that also makes "two runs share one
-- transaction" impossible rather than merely unlikely.
CREATE UNIQUE INDEX idx_recurring_runs_transaction ON recurring_runs (transaction_id) WHERE transaction_id IS NOT NULL;
