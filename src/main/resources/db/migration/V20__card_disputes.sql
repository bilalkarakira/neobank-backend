-- Disputes (P6a): a customer disputes a cleared card payment, the bank gives provisional credit and sends a chargeback through the
-- network; the shop's bank may answer with a representment. The chargeback and the representment are clearing items of their own,
-- so settlement and the books check count them like a refund and a purchase.

-- ---- clearing items: the two new kinds, items that do not come from a clearing file, and the business date on the item itself ----

ALTER TABLE clearing_items DROP CONSTRAINT clearing_items_kind_check;
ALTER TABLE clearing_items ALTER COLUMN kind TYPE VARCHAR(16);
ALTER TABLE clearing_items ADD CONSTRAINT clearing_items_kind_check CHECK (kind IN ('PURCHASE', 'REFUND', 'CHARGEBACK', 'REPRESENTMENT'));

-- CHARGEBACK_SENT: counted in the books at once (the provisional credit left the card settlement account) but not settled until
-- the network accepts it (CHARGEBACK_POSTED); CHARGEBACK_REFUSED: the network refused it and the credit was taken back
ALTER TABLE clearing_items DROP CONSTRAINT clearing_items_result_check;
ALTER TABLE clearing_items ALTER COLUMN result TYPE VARCHAR(24);
ALTER TABLE clearing_items ADD CONSTRAINT clearing_items_result_check CHECK (result IN
    ('PENDING', 'POSTED', 'FORCE_POSTED', 'REFUND_POSTED', 'DUPLICATE', 'REJECTED',
     'CHARGEBACK_SENT', 'CHARGEBACK_POSTED', 'CHARGEBACK_REFUSED', 'REPRESENTMENT_POSTED'));

ALTER TABLE clearing_items ALTER COLUMN file_id DROP NOT NULL;
ALTER TABLE clearing_items ADD COLUMN business_date DATE;
UPDATE clearing_items i SET business_date = f.business_date FROM clearing_files f WHERE f.id = i.file_id;
CREATE INDEX idx_clearing_items_business_date ON clearing_items (business_date);

-- ---- dispute files (.dsp): chargebacks this bank sends (OUT) and representments it receives (IN) ----

CREATE SEQUENCE dispute_file_seq;

CREATE TABLE dispute_files (
    id            UUID         PRIMARY KEY,
    file_name     VARCHAR(120) NOT NULL UNIQUE,
    file_id       VARCHAR(32),
    direction     VARCHAR(3)   NOT NULL CHECK (direction IN ('OUT', 'IN')),
    business_date DATE,
    -- OUT: CREATED (rows decided, file maybe not on disk yet), WRITTEN, ACKED, REJECTED (the whole file was refused)
    -- IN:  RECEIVED (being posted), DONE, REJECTED
    status        VARCHAR(10)  NOT NULL CHECK (status IN ('CREATED', 'WRITTEN', 'ACKED', 'RECEIVED', 'DONE', 'REJECTED')),
    reason        VARCHAR(200),
    item_count    INT          NOT NULL DEFAULT 0,
    control_total BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    -- IN files only: our acknowledgement is on disk
    ack_written   BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_dispute_files_in_id ON dispute_files (file_id) WHERE direction = 'IN' AND status <> 'REJECTED';

ALTER TABLE clearing_items ADD COLUMN dispute_file_id UUID REFERENCES dispute_files (id);
-- the backstop that makes posting one received representment twice impossible, however many ingests run at once
CREATE UNIQUE INDEX uq_clearing_items_dispute_record ON clearing_items (dispute_file_id, record_no) WHERE dispute_file_id IS NOT NULL;

-- ---- the disputes ----

CREATE TABLE card_disputes (
    id                    UUID           PRIMARY KEY,
    -- a payment can be disputed once
    authorization_id      UUID           NOT NULL UNIQUE REFERENCES authorizations (id),
    card_id               UUID           NOT NULL REFERENCES cards (id),
    user_id               UUID           NOT NULL REFERENCES users (id),
    account_id            UUID           NOT NULL REFERENCES accounts (id),
    merchant_name         VARCHAR(40),
    last4                 VARCHAR(4)     NOT NULL,
    amount                NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    reason                VARCHAR(20)    NOT NULL CHECK (reason IN ('UNAUTHORIZED', 'NOT_RECEIVED', 'DUPLICATE', 'WRONG_AMOUNT', 'OTHER')),
    note                  VARCHAR(200),
    -- FILED: credit given, chargeback sent or waiting; OPEN: the network took it, the shop may answer; WON / LOST: closed
    status                VARCHAR(6)     NOT NULL CHECK (status IN ('FILED', 'OPEN', 'WON', 'LOST')),
    outcome               VARCHAR(200),
    -- a representment that arrived after the dispute was already WON
    late_representment    BOOLEAN        NOT NULL DEFAULT FALSE,
    filed_at              TIMESTAMPTZ    NOT NULL,
    opened_at             TIMESTAMPTZ,
    closed_at             TIMESTAMPTZ,
    chargeback_item_id    UUID           REFERENCES clearing_items (id),
    representment_item_id UUID           REFERENCES clearing_items (id),
    dispute_file_id       UUID           REFERENCES dispute_files (id),
    file_record           INT
);
CREATE INDEX idx_card_disputes_user ON card_disputes (user_id, filed_at DESC);
CREATE INDEX idx_card_disputes_open ON card_disputes (status, opened_at);
CREATE UNIQUE INDEX uq_card_disputes_file_record ON card_disputes (dispute_file_id, file_record) WHERE dispute_file_id IS NOT NULL;
