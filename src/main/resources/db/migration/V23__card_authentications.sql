-- Cardholder authentication for online payments (P6d-2): a payment above the threshold is declined with 1A and an authentication is created.
-- The customer approves or rejects it in the app; the shop's next request carries its reference as proof. The proof is used at most once:
-- used_at is set in the same transaction as the authorization it pays for.

CREATE TABLE card_authentications (
    id            UUID PRIMARY KEY,
    card_id       UUID           NOT NULL REFERENCES cards (id),
    reference     VARCHAR(8)     NOT NULL UNIQUE,
    amount        NUMERIC(19, 2) NOT NULL,
    merchant_id   VARCHAR(15)    NOT NULL,
    merchant_name VARCHAR(40)    NOT NULL,
    status        VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at    TIMESTAMPTZ    NOT NULL,
    expires_at    TIMESTAMPTZ    NOT NULL,
    decided_at    TIMESTAMPTZ,
    used_at       TIMESTAMPTZ
);

CREATE INDEX idx_card_authentications_card_pending ON card_authentications (card_id) WHERE status = 'PENDING';
