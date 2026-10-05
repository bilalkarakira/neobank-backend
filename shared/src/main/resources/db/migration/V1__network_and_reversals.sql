-- cardnet database, part 1: what the network and the reversal queue need. (The acquirer's tables come with the acquirer.)
-- No clear card number is ever stored here: only masked forms (first 6 + last 4), a keyed fingerprint for grouping a
-- card's stand-in total, and AES-GCM ciphertext (PanVault) where a message has to be rebuilt later.

CREATE SEQUENCE stan_seq MINVALUE 1 MAXVALUE 999999 CYCLE;
CREATE SEQUENCE rrn_seq;

-- A reversal that must reach the issuer, however long it takes. Owned by the process that must deliver it.
CREATE TABLE pending_reversals (
    id                 UUID           PRIMARY KEY,
    owner              VARCHAR(10)    NOT NULL CHECK (owner IN ('ACQUIRER', 'NETWORK')),
    stan               VARCHAR(6)     NOT NULL,
    rrn                VARCHAR(12)    NOT NULL,
    masked_pan         VARCHAR(19)    NOT NULL,
    amount             NUMERIC(19, 2) NOT NULL,
    request_ciphertext BYTEA          NOT NULL,
    status             VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    attempts           INT            NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ    NOT NULL,
    deadline_at        TIMESTAMPTZ    NOT NULL,
    reason             VARCHAR(40),
    last_response      VARCHAR(2),
    acq_transaction_id UUID,
    created_at         TIMESTAMPTZ    NOT NULL,
    completed_at       TIMESTAMPTZ
);
-- one reversal per original request, per owner
CREATE UNIQUE INDEX uq_pending_reversals_original ON pending_reversals (owner, stan, rrn);
CREATE INDEX idx_pending_reversals_due ON pending_reversals (owner, status, next_attempt_at);

-- Payments the network approved ON ITS OWN while the issuer was unavailable, to be told to the issuer afterwards.
CREATE TABLE net_standin_advices (
    id                 UUID           PRIMARY KEY,
    fingerprint        VARCHAR(64)    NOT NULL,
    masked_pan         VARCHAR(19)    NOT NULL,
    acquirer_id        VARCHAR(11)    NOT NULL,
    stan               VARCHAR(6)     NOT NULL,
    rrn                VARCHAR(12)    NOT NULL,
    de7                VARCHAR(10)    NOT NULL,
    amount             NUMERIC(19, 2) NOT NULL,
    auth_code          VARCHAR(6)     NOT NULL,
    request_ciphertext BYTEA          NOT NULL,
    status             VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'DELIVERED', 'CANCELLED')),
    cancel_reason      VARCHAR(40),
    attempts           INT            NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL,
    delivered_at       TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_net_standin_advices_request ON net_standin_advices (acquirer_id, stan, rrn);
CREATE INDEX idx_net_standin_advices_card_day ON net_standin_advices (fingerprint, created_at);
CREATE INDEX idx_net_standin_advices_pending ON net_standin_advices (status, created_at);

-- One row per request the network handled.
CREATE TABLE net_transactions (
    id           UUID           PRIMARY KEY,
    created_at   TIMESTAMPTZ    NOT NULL,
    acquirer_id  VARCHAR(11),
    mti          VARCHAR(4)     NOT NULL,
    stan         VARCHAR(6),
    rrn          VARCHAR(12),
    masked_pan   VARCHAR(19),
    amount       NUMERIC(19, 2),
    route        VARCHAR(10)    NOT NULL CHECK (route IN ('ISSUER', 'STAND_IN', 'REFUSED', 'CANCELLED')),
    issuer_code  VARCHAR(2),
    answer_code  VARCHAR(2),
    latency_ms   INT,
    late         BOOLEAN        NOT NULL DEFAULT FALSE,
    note         VARCHAR(80)
);
CREATE INDEX idx_net_transactions_created ON net_transactions (created_at);
