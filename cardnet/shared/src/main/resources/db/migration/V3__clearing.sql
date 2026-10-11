-- cardnet database, part 3: capture and clearing (P4). Still no clear card number anywhere: the presentments the network
-- keeps for rewriting a file are AES-GCM ciphertext (PanVault), everything else is masked.

-- ---- acquirer -------------------------------------------------------------------------------------------------

ALTER TABLE acq_transactions DROP CONSTRAINT acq_transactions_status_check;
ALTER TABLE acq_transactions ADD CONSTRAINT acq_transactions_status_check CHECK (status IN
    ('PENDING', 'APPROVED', 'DECLINED', 'REVERSING', 'REVERSED', 'REVERSAL_FAILED',
     'REFUND_DUE', 'BATCHED', 'CLEARED', 'CLEARING_REJECTED'));
ALTER TABLE acq_transactions ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'PURCHASE' CHECK (kind IN ('PURCHASE', 'REFUND'));
-- the amount that will clear when it differs from the authorized one (a tip); null means "as authorized"
ALTER TABLE acq_transactions ADD COLUMN final_amount NUMERIC(19, 2) CHECK (final_amount > 0);
ALTER TABLE acq_transactions ADD COLUMN refund_of UUID REFERENCES acq_transactions (id);
ALTER TABLE acq_transactions ADD COLUMN batch_id UUID;
-- the number of its presentment inside the batch file (1, 2, ...): how an acknowledgement item finds its payment
ALTER TABLE acq_transactions ADD COLUMN batch_record INT;
CREATE INDEX idx_acq_transactions_batch ON acq_transactions (batch_id);
CREATE INDEX idx_acq_transactions_refund_of ON acq_transactions (refund_of);

CREATE SEQUENCE batch_seq;

CREATE TABLE acq_batches (
    id             UUID         PRIMARY KEY,
    file_id        VARCHAR(32)  NOT NULL UNIQUE,
    file_name      VARCHAR(120) NOT NULL UNIQUE,
    business_date  DATE         NOT NULL,
    item_count     INT          NOT NULL,
    control_total  BIGINT       NOT NULL,
    -- CREATED: payments claimed, file not written yet (a crash here is repaired at start); SUBMITTED: file written
    status         VARCHAR(10)  NOT NULL CHECK (status IN ('CREATED', 'SUBMITTED', 'ACCEPTED', 'REJECTED')),
    created_at     TIMESTAMPTZ  NOT NULL,
    submitted_at   TIMESTAMPTZ,
    acked_at       TIMESTAMPTZ,
    ack_reason     VARCHAR(200)
);

-- ---- network --------------------------------------------------------------------------------------------------

CREATE SEQUENCE clearing_file_seq;

-- One row per clearing file an acquirer left for the network (by file NAME, so a file is looked at once).
CREATE TABLE net_clearing_files (
    id             UUID         PRIMARY KEY,
    file_name      VARCHAR(120) NOT NULL UNIQUE,
    file_id        VARCHAR(32),
    sender         VARCHAR(32),
    business_date  DATE,
    item_count     INT          NOT NULL DEFAULT 0,
    control_total  BIGINT       NOT NULL DEFAULT 0,
    status         VARCHAR(10)  NOT NULL CHECK (status IN ('REJECTED', 'ACCEPTED')),
    reason         VARCHAR(200),
    received_at    TIMESTAMPTZ  NOT NULL,
    ack_written    BOOLEAN      NOT NULL DEFAULT FALSE,
    ack_written_at TIMESTAMPTZ
);
-- a file id is processed once: a second file with the same id is refused, not processed again
CREATE UNIQUE INDEX uq_net_clearing_files_id ON net_clearing_files (file_id) WHERE status = 'ACCEPTED';

-- The per-issuer files the network wrote (or still has to write: the row exists before the file does).
CREATE TABLE net_clearing_outfiles (
    id             UUID         PRIMARY KEY,
    file_row       UUID         NOT NULL REFERENCES net_clearing_files (id),
    issuer_prefix  VARCHAR(19)  NOT NULL,
    file_id        VARCHAR(32)  NOT NULL UNIQUE,
    file_name      VARCHAR(120) NOT NULL UNIQUE,
    item_count     INT          NOT NULL,
    control_total  BIGINT       NOT NULL,
    written        BOOLEAN      NOT NULL DEFAULT FALSE,
    written_at     TIMESTAMPTZ,
    ack_status     VARCHAR(10)  CHECK (ack_status IN ('ACCEPTED', 'REJECTED', 'INVALID')),
    ack_reason     VARCHAR(200),
    acked_at       TIMESTAMPTZ
);

CREATE TABLE net_clearing_items (
    id                     UUID           PRIMARY KEY,
    file_row               UUID           NOT NULL REFERENCES net_clearing_files (id),
    record_no              INT            NOT NULL,
    outfile                UUID           REFERENCES net_clearing_outfiles (id),
    out_record             INT,
    kind                   VARCHAR(10)    NOT NULL CHECK (kind IN ('PURCHASE', 'REFUND')),
    stan                   VARCHAR(6)     NOT NULL,
    rrn                    VARCHAR(12)    NOT NULL,
    masked_pan             VARCHAR(19)    NOT NULL,
    amount                 NUMERIC(19, 2) NOT NULL,
    -- signed: a refund carries the negative of what the same purchase would have cost (posted in P5)
    merchant_discount      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    interchange            NUMERIC(19, 2) NOT NULL DEFAULT 0,
    scheme_fee_acquirer    NUMERIC(19, 2) NOT NULL DEFAULT 0,
    scheme_fee_issuer      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    -- null while the issuer has not answered; REFUSED is set by the network itself
    result                 VARCHAR(20),
    reason                 VARCHAR(120),
    presentment_ciphertext BYTEA,
    UNIQUE (file_row, record_no)
);
CREATE INDEX idx_net_clearing_items_outfile ON net_clearing_items (outfile);
