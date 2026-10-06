-- Settlement (P5): the net money movement between the banks for the cleared card payments of a business day, and the proof that
-- authorizations, clearing and settlement agree.

-- NeoBank's bookkeeping of the settlement: what it has paid out to the settlement bank (net), and the interchange it earned less
-- the scheme fees it paid. Both are bank-internal and may hold any sign.
INSERT INTO accounts (id, user_id, iban, type, currency, status, nickname, balance, created_at)
VALUES ('00000000-0000-0000-0000-000000000004', NULL, 'SYSTEM-INTERCHANGE-INCOME', 'SYSTEM', 'USD', 'ACTIVE',
        'Card income (net of scheme fees)', 0, now()),
       ('00000000-0000-0000-0000-000000000005', NULL, 'SYSTEM-SETTLEMENT-BANK', 'SYSTEM', 'USD', 'ACTIVE',
        'Settlement bank (net paid out)', 0, now());

-- the business date of a clearing file (from its header): which day a cleared item belongs to
ALTER TABLE clearing_files ADD COLUMN business_date DATE;

-- one row per settlement statement the network left for NeoBank
CREATE TABLE settlement_runs (
    id            UUID           PRIMARY KEY,
    file_name     VARCHAR(120)   NOT NULL UNIQUE,
    -- the network's run number; a run is posted once (unique among the files that were understood)
    run_number    VARCHAR(16),
    business_date DATE,
    value_date    DATE,
    status        VARCHAR(10)    NOT NULL CHECK (status IN ('MATCHED', 'DISPUTED', 'REFUSED')),
    -- the figures in the file ...
    purchases     NUMERIC(19, 2),
    refunds       NUMERIC(19, 2),
    interchange   NUMERIC(19, 2),
    scheme_fee    NUMERIC(19, 2),
    net           NUMERIC(19, 2),
    item_count    INT,
    -- ... and what NeoBank computed itself from the items it posted
    own_purchases   NUMERIC(19, 2),
    own_refunds     NUMERIC(19, 2),
    own_interchange NUMERIC(19, 2),
    own_scheme_fee  NUMERIC(19, 2),
    own_net         NUMERIC(19, 2),
    own_item_count  INT,
    -- why it was disputed or refused
    reason        VARCHAR(400),
    received_at   TIMESTAMPTZ    NOT NULL
);
CREATE UNIQUE INDEX uq_settlement_runs_number ON settlement_runs (run_number) WHERE status = 'MATCHED';

-- which settlement run an item was settled by (null = not settled yet)
ALTER TABLE clearing_items ADD COLUMN settlement_run_id UUID REFERENCES settlement_runs (id);
CREATE INDEX idx_clearing_items_unsettled ON clearing_items (settlement_run_id) WHERE settlement_run_id IS NULL;
