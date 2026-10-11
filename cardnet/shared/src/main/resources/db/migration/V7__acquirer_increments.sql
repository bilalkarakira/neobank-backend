-- cardnet database, part 7: incremental authorizations at the acquirer (P6c). A shop that does not know the final price (a fuel pump)
-- asks the bank for more on the same payment. Each request is its own message with its own STAN and RRN, kept here; the payment
-- carries the sum of the approved ones, which is added to what it will clear for.

ALTER TABLE acq_transactions ADD COLUMN increment_total NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (increment_total >= 0);

CREATE TABLE acq_increments (
    id                 UUID           PRIMARY KEY,
    payment_id         UUID           NOT NULL REFERENCES acq_transactions (id),
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    stan               VARCHAR(6)     NOT NULL,
    rrn                VARCHAR(12)    NOT NULL,
    amount             NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    status             VARCHAR(16)    NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'REVERSING', 'REVERSED', 'REVERSAL_FAILED')),
    response_code      VARCHAR(2),
    reason             VARCHAR(120),
    timed_out          BOOLEAN        NOT NULL DEFAULT FALSE,
    request_ciphertext BYTEA          NOT NULL
);
CREATE UNIQUE INDEX uq_acq_increments_request ON acq_increments (stan, rrn);
CREATE INDEX idx_acq_increments_payment ON acq_increments (payment_id);
