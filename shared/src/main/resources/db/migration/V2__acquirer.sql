-- cardnet database, part 2: the acquirer's view of the payments its terminals sent.
-- Like everything here it holds no clear card number: the masked form for display, and AES-GCM ciphertext of the request
-- (without any PIN data) so a payment can be voided or resent later and, in a later phase, cleared.

CREATE TABLE acq_transactions (
    id                 UUID           PRIMARY KEY,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    stan               VARCHAR(6)     NOT NULL,
    rrn                VARCHAR(12)    NOT NULL,
    terminal_id        VARCHAR(8)     NOT NULL,
    merchant_id        VARCHAR(15)    NOT NULL,
    merchant_name      VARCHAR(40)    NOT NULL,
    mcc                VARCHAR(4)     NOT NULL,
    masked_pan         VARCHAR(19)    NOT NULL,
    last4              VARCHAR(4)     NOT NULL,
    amount             NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency           VARCHAR(3)     NOT NULL,
    de7                VARCHAR(10)    NOT NULL,
    pin_used           BOOLEAN        NOT NULL DEFAULT FALSE,
    status             VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'REVERSING', 'REVERSED', 'REVERSAL_FAILED')),
    response_code      VARCHAR(2),
    auth_code          VARCHAR(6),
    reason             VARCHAR(80),
    timed_out          BOOLEAN        NOT NULL DEFAULT FALSE,
    request_ciphertext BYTEA          NOT NULL
);
CREATE UNIQUE INDEX uq_acq_transactions_request ON acq_transactions (stan, rrn);
CREATE INDEX idx_acq_transactions_created ON acq_transactions (created_at);
