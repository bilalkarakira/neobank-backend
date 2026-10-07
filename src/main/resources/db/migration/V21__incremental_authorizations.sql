-- Incremental authorizations (P6c): a shop that does not know the final price (a fuel pump, a hotel) asks for more on the same payment.
-- An increment is an authorization of its own (its own duplicate key, hold, expiry and reversal) that points at the first one.
-- When the first one is cleared for the total, its increments stop holding money: status MERGED.

ALTER TABLE authorizations ADD COLUMN parent_id UUID REFERENCES authorizations (id);
CREATE INDEX idx_authorizations_parent ON authorizations (parent_id) WHERE parent_id IS NOT NULL;

ALTER TABLE authorizations DROP CONSTRAINT authorizations_status_check;
ALTER TABLE authorizations ADD CONSTRAINT authorizations_status_check
    CHECK (status IN ('AUTHORIZED', 'DECLINED', 'REVERSED', 'EXPIRED', 'CAPTURED', 'MERGED'));
