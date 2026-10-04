CREATE SEQUENCE account_number_seq START WITH 1000000001;

CREATE TABLE accounts (
    id         UUID           PRIMARY KEY,
    user_id    UUID           NOT NULL REFERENCES users (id),
    iban       VARCHAR(34)    NOT NULL UNIQUE,
    type       VARCHAR(20)    NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    status     VARCHAR(20)    NOT NULL,
    nickname   VARCHAR(40),
    -- cached value; becomes ledger-derived in F5
    balance    NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    created_at TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_accounts_user_id ON accounts (user_id);
