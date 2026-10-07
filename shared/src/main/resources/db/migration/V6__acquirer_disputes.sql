-- cardnet database, part 6: the acquirer's side of disputes (P6a). No card number anywhere: a chargeback points at the payment row.

CREATE SEQUENCE representment_seq;

-- one row per chargeback file that arrived (or was refused as a whole), and whether our acknowledgement is on disk
CREATE TABLE acq_dispute_files (
    file_name    VARCHAR(120) PRIMARY KEY,
    file_id      VARCHAR(32),
    status       VARCHAR(10)  NOT NULL CHECK (status IN ('ACCEPTED', 'REJECTED')),
    reason       VARCHAR(200),
    received_at  TIMESTAMPTZ  NOT NULL,
    ack_written  BOOLEAN      NOT NULL DEFAULT FALSE
);

-- the result for each record of an accepted file, kept so the acknowledgement can be rewritten after a crash
CREATE TABLE acq_dispute_items (
    file_name VARCHAR(120) NOT NULL REFERENCES acq_dispute_files (file_name),
    record_no INT          NOT NULL,
    result    VARCHAR(24)  NOT NULL,
    reason    VARCHAR(200),
    PRIMARY KEY (file_name, record_no)
);

CREATE TABLE acq_disputes (
    id             UUID           PRIMARY KEY,
    payment_id     UUID           NOT NULL UNIQUE REFERENCES acq_transactions (id),
    file_name      VARCHAR(120)   NOT NULL,
    record_no      INT            NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    reason         VARCHAR(40)    NOT NULL,
    note           VARCHAR(200),
    status         VARCHAR(12)    NOT NULL CHECK (status IN ('RECEIVED', 'REPRESENTED')),
    received_at    TIMESTAMPTZ    NOT NULL,
    rep_note       VARCHAR(200),
    represented_at TIMESTAMPTZ,
    rep_file_id    VARCHAR(32)    UNIQUE,
    rep_written    BOOLEAN        NOT NULL DEFAULT FALSE,
    rep_answer     VARCHAR(200),
    UNIQUE (file_name, record_no)
);
