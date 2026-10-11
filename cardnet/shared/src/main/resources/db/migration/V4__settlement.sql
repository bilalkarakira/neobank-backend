-- cardnet database, part 4: settlement (P5). Totals only: no card number anywhere.

CREATE SEQUENCE settlement_seq;

-- One settlement run per business date and attempt (a second run for a date only settles what the first one did not).
CREATE TABLE net_settlements (
    id             UUID         PRIMARY KEY,
    run_number     VARCHAR(16)  NOT NULL UNIQUE,
    business_date  DATE         NOT NULL,
    value_date     DATE         NOT NULL,
    item_count     INT          NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    -- the row exists before its files do, so a crash in between is repaired on the next beat
    files_written  BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE net_settlement_lines (
    id             UUID           PRIMARY KEY,
    settlement_id  UUID           NOT NULL REFERENCES net_settlements (id),
    participant    VARCHAR(32)    NOT NULL,
    role           VARCHAR(10)    NOT NULL CHECK (role IN ('ISSUER', 'ACQUIRER', 'NETWORK')),
    purchases      NUMERIC(19, 2) NOT NULL,
    refunds        NUMERIC(19, 2) NOT NULL,
    interchange    NUMERIC(19, 2) NOT NULL,
    scheme_fee     NUMERIC(19, 2) NOT NULL,
    -- signed: positive = the participant RECEIVES money, negative = it PAYS
    net            NUMERIC(19, 2) NOT NULL,
    item_count     INT            NOT NULL,
    UNIQUE (settlement_id, participant, role)
);

-- null = not settled yet
ALTER TABLE net_clearing_items ADD COLUMN settlement_id UUID REFERENCES net_settlements (id);
CREATE INDEX idx_net_clearing_items_unsettled ON net_clearing_items (settlement_id) WHERE settlement_id IS NULL;
