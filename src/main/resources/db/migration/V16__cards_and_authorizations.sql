-- Cards issued by NeoBank (test BIN 999900). The clear PAN and PIN are never stored: only a keyed hash of each.
CREATE TABLE cards (
    id            UUID           PRIMARY KEY,
    user_id       UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    account_id    UUID           NOT NULL REFERENCES accounts (id),
    pan_hash      VARCHAR(64)       NOT NULL UNIQUE,
    last4         VARCHAR(4)        NOT NULL,
    bin           VARCHAR(6)        NOT NULL,
    expiry        VARCHAR(4)        NOT NULL,
    status        VARCHAR(20)    NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CANCELLED')),
    freeze_reason VARCHAR(20)    CHECK (freeze_reason IN ('CUSTOMER', 'PIN_TRIES', 'ADMIN')),
    pin_hash      VARCHAR(64)       NOT NULL,
    pin_failures  INT            NOT NULL DEFAULT 0,
    daily_limit   NUMERIC(19, 2) NOT NULL CHECK (daily_limit > 0),
    nickname      VARCHAR(40),
    created_at    TIMESTAMPTZ    NOT NULL,
    cancelled_at  TIMESTAMPTZ
);
CREATE INDEX idx_cards_user ON cards (user_id);
CREATE INDEX idx_cards_account ON cards (account_id);

-- One row per request the issuer decided (approved or declined). Only AUTHORIZED rows hold money.
CREATE TABLE authorizations (
    id              UUID           PRIMARY KEY,
    card_id         UUID           REFERENCES cards (id),
    account_id      UUID           REFERENCES accounts (id),
    pan_hash        VARCHAR(64)       NOT NULL,
    last4           VARCHAR(4)        NOT NULL,
    mti             VARCHAR(4)        NOT NULL,
    stan            VARCHAR(6)        NOT NULL,
    rrn             VARCHAR(12)       NOT NULL,
    terminal_id     VARCHAR(8)     NOT NULL,
    merchant_id     VARCHAR(15)    NOT NULL,
    merchant_name   VARCHAR(40),
    mcc             VARCHAR(4),
    acquirer_id     VARCHAR(11)    NOT NULL,
    processing_code VARCHAR(6),
    entry_mode      VARCHAR(3),
    local_date      VARCHAR(4)        NOT NULL,
    transmitted_at  VARCHAR(10)       NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL CHECK (amount >= 0),
    currency        VARCHAR(3)        NOT NULL,
    response_code   VARCHAR(2)        NOT NULL,
    auth_code       VARCHAR(6),
    status          VARCHAR(20)    NOT NULL CHECK (status IN ('AUTHORIZED', 'DECLINED', 'REVERSED', 'EXPIRED', 'CAPTURED')),
    created_at      TIMESTAMPTZ    NOT NULL,
    expires_at      TIMESTAMPTZ,
    released_at     TIMESTAMPTZ
);
-- the duplicate key, and the backstop that makes two simultaneous duplicates impossible
CREATE UNIQUE INDEX uq_authorizations_duplicate
    ON authorizations (pan_hash, acquirer_id, stan, rrn, terminal_id, local_date);
-- how a reversal finds its original
CREATE INDEX idx_authorizations_original ON authorizations (pan_hash, acquirer_id, stan, transmitted_at);
-- the holds sum of an account (taken on every debit of a customer account)
CREATE INDEX idx_authorizations_holds ON authorizations (account_id) WHERE status = 'AUTHORIZED';
CREATE INDEX idx_authorizations_card ON authorizations (card_id, created_at);
