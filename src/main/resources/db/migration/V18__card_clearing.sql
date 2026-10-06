-- Capture and clearing (P4c): the issuer reads the clearing files, posts the real debit of each cleared card payment and
-- keeps what the customers could not cover as a receivable.

-- What NeoBank owes the card network for cleared payments (customer -> here), and what customers owe NeoBank for the part of a
-- cleared payment their account could not cover (here -> settlement). Both are bank-internal and may go negative.
INSERT INTO accounts (id, user_id, iban, type, currency, status, nickname, balance, created_at)
VALUES ('00000000-0000-0000-0000-000000000003', NULL, 'SYSTEM-CARD-SETTLEMENT', 'SYSTEM', 'USD', 'ACTIVE',
        'Card settlement payable', 0, now()),
       ('00000000-0000-0000-0000-000000000006', NULL, 'SYSTEM-CARD-RECEIVABLE', 'SYSTEM', 'USD', 'ACTIVE',
        'Card shortfall receivable', 0, now());

ALTER TABLE authorizations ADD COLUMN captured_amount NUMERIC(19, 2);
ALTER TABLE authorizations ADD COLUMN captured_at TIMESTAMPTZ;
-- cleared although this bank never authorized it (the shop was paid by its bank, so the presentment is honoured)
ALTER TABLE authorizations ADD COLUMN forced BOOLEAN NOT NULL DEFAULT FALSE;

-- One row per clearing file the network handed over (by network file id, so a file is processed once).
CREATE TABLE clearing_files (
    id            UUID         PRIMARY KEY,
    file_name     VARCHAR(120) NOT NULL UNIQUE,
    network_file_id VARCHAR(32),
    status        VARCHAR(10)  NOT NULL CHECK (status IN ('RECEIVED', 'DONE', 'REJECTED')),
    reason        VARCHAR(200),
    item_count    INT          NOT NULL DEFAULT 0,
    control_total BIGINT       NOT NULL DEFAULT 0,
    received_at   TIMESTAMPTZ  NOT NULL,
    ack_written   BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_clearing_files_network_id ON clearing_files (network_file_id) WHERE status <> 'REJECTED';

CREATE TABLE clearing_items (
    id               UUID           PRIMARY KEY,
    file_id          UUID           NOT NULL REFERENCES clearing_files (id),
    record_no        INT            NOT NULL,
    kind             VARCHAR(10)    NOT NULL CHECK (kind IN ('PURCHASE', 'REFUND')),
    amount           NUMERIC(19, 2) NOT NULL,
    last4            VARCHAR(4)     NOT NULL,
    merchant_name    VARCHAR(40),
    authorization_id UUID           REFERENCES authorizations (id),
    card_id          UUID           REFERENCES cards (id),
    -- PENDING only inside the transaction that decides it
    result           VARCHAR(20)    NOT NULL CHECK (result IN ('PENDING', 'POSTED', 'FORCE_POSTED', 'REFUND_POSTED', 'DUPLICATE', 'REJECTED')),
    reason           VARCHAR(120),
    covered          NUMERIC(19, 2) NOT NULL DEFAULT 0,
    shortfall        NUMERIC(19, 2) NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL,
    -- the backstop that makes posting one item twice impossible, however many ingests run at once
    CONSTRAINT uq_clearing_items_record UNIQUE (file_id, record_no)
);
CREATE INDEX idx_clearing_items_authorization ON clearing_items (authorization_id);

-- What a customer owes the bank because a cleared payment was bigger than what their account could cover.
CREATE TABLE card_receivables (
    id               UUID           PRIMARY KEY,
    user_id          UUID           NOT NULL REFERENCES users (id),
    account_id       UUID           NOT NULL REFERENCES accounts (id),
    clearing_item_id UUID           REFERENCES clearing_items (id),
    amount           NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    outstanding      NUMERIC(19, 2) NOT NULL CHECK (outstanding >= 0 AND outstanding <= amount),
    created_at       TIMESTAMPTZ    NOT NULL,
    settled_at       TIMESTAMPTZ
);
CREATE INDEX idx_card_receivables_open ON card_receivables (account_id) WHERE outstanding > 0;
