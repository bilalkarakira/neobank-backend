-- Everything an administrator does that changes the bank's state is written here, in the same transaction as the
-- change itself. Like the ledger, the log is append-only: mistakes are corrected by new rows, never by edits.
CREATE TABLE admin_actions (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_id   UUID         NOT NULL REFERENCES users (id),
    action     VARCHAR(30)  NOT NULL,                 -- FREEZE_ACCOUNT | UNFREEZE_ACCOUNT | RUN_INTEREST | RUN_RECURRING
    account_id UUID REFERENCES accounts (id),
    reason     VARCHAR(200),
    detail     VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_admin_actions_created ON admin_actions (created_at DESC, id DESC);

CREATE FUNCTION forbid_admin_action_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'admin_actions is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER admin_actions_append_only
    BEFORE UPDATE OR DELETE ON admin_actions
    FOR EACH ROW EXECUTE FUNCTION forbid_admin_action_mutation();
