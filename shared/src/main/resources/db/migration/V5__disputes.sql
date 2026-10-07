-- cardnet database, part 5: disputes (P6a). Chargebacks and representments become items of their own, so settlement counts them.

ALTER TABLE net_clearing_items DROP CONSTRAINT net_clearing_items_kind_check;
ALTER TABLE net_clearing_items ALTER COLUMN kind TYPE VARCHAR(16);
ALTER TABLE net_clearing_items ADD CONSTRAINT net_clearing_items_kind_check
    CHECK (kind IN ('PURCHASE', 'REFUND', 'CHARGEBACK', 'REPRESENTMENT'));

-- REPRESENTMENT_ACCEPTED is longer than the old limit of 20
ALTER TABLE net_clearing_items ALTER COLUMN result TYPE VARCHAR(24);

-- an item no longer always comes from an acquirer's clearing file, so what settlement needs is kept on the item itself
ALTER TABLE net_clearing_items ADD COLUMN issuer_prefix VARCHAR(19);
ALTER TABLE net_clearing_items ADD COLUMN acquirer_id VARCHAR(32);
ALTER TABLE net_clearing_items ADD COLUMN business_date DATE;
UPDATE net_clearing_items i SET issuer_prefix = o.issuer_prefix FROM net_clearing_outfiles o WHERE o.id = i.outfile;
UPDATE net_clearing_items i SET acquirer_id = f.sender, business_date = f.business_date FROM net_clearing_files f WHERE f.id = i.file_row;
ALTER TABLE net_clearing_items ALTER COLUMN file_row DROP NOT NULL;

-- a chargeback points at the purchase it disputes, a representment at the chargeback it answers
ALTER TABLE net_clearing_items ADD COLUMN original_item UUID REFERENCES net_clearing_items (id);
CREATE UNIQUE INDEX uq_net_clearing_items_one_chargeback ON net_clearing_items (original_item) WHERE kind = 'CHARGEBACK';
CREATE UNIQUE INDEX uq_net_clearing_items_one_representment ON net_clearing_items (original_item) WHERE kind = 'REPRESENTMENT';

CREATE SEQUENCE dispute_file_seq;

-- one row per dispute file someone left for the network (chargebacks from an issuer, representments from an acquirer)
CREATE TABLE net_dispute_files (
    id             UUID         PRIMARY KEY,
    file_name      VARCHAR(120) NOT NULL UNIQUE,
    file_id        VARCHAR(32),
    sender         VARCHAR(32),
    business_date  DATE,
    direction      VARCHAR(15)  CHECK (direction IN ('CHARGEBACKS', 'REPRESENTMENTS')),
    item_count     INT          NOT NULL DEFAULT 0,
    control_total  BIGINT       NOT NULL DEFAULT 0,
    status         VARCHAR(10)  NOT NULL CHECK (status IN ('REJECTED', 'ACCEPTED')),
    reason         VARCHAR(200),
    received_at    TIMESTAMPTZ  NOT NULL,
    ack_written    BOOLEAN      NOT NULL DEFAULT FALSE,
    ack_written_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_net_dispute_files_id ON net_dispute_files (file_id) WHERE status = 'ACCEPTED';

-- the file the network writes for the other side of a dispute file (the row exists before the file does)
CREATE TABLE net_dispute_forwards (
    id           UUID         PRIMARY KEY,
    dispute_file UUID         NOT NULL REFERENCES net_dispute_files (id),
    target       VARCHAR(32)  NOT NULL,
    file_id      VARCHAR(32)  NOT NULL UNIQUE,
    file_name    VARCHAR(120) NOT NULL UNIQUE,
    item_count   INT          NOT NULL,
    control_total BIGINT      NOT NULL,
    written      BOOLEAN      NOT NULL DEFAULT FALSE,
    written_at   TIMESTAMPTZ,
    ack_status   VARCHAR(10)  CHECK (ack_status IN ('ACCEPTED', 'REJECTED', 'INVALID')),
    ack_reason   VARCHAR(200),
    acked_at     TIMESTAMPTZ
);

ALTER TABLE net_clearing_items ADD COLUMN dispute_file UUID REFERENCES net_dispute_files (id);
ALTER TABLE net_clearing_items ADD COLUMN forward UUID REFERENCES net_dispute_forwards (id);
ALTER TABLE net_clearing_items ADD COLUMN forward_record INT;
CREATE UNIQUE INDEX uq_net_clearing_items_dispute_record ON net_clearing_items (dispute_file, record_no) WHERE dispute_file IS NOT NULL;
