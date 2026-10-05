-- What a customer needs to recognise a login in the "where you are logged in" list: a readable device label
-- ("Chrome on Windows"), the address the login came from, and when the login started. All three are copied from token
-- to token on rotation, so they describe the LOGIN and survive the daily purge of its older, expired tokens. (Reading
-- them from "the oldest token still in the table" would make the start time drift forward and lose the device once
-- the first token had been purged.) Rows from before this migration have no device or address.
ALTER TABLE refresh_tokens ADD COLUMN device VARCHAR(60);
ALTER TABLE refresh_tokens ADD COLUMN ip VARCHAR(45);
ALTER TABLE refresh_tokens ADD COLUMN family_started_at TIMESTAMPTZ;

UPDATE refresh_tokens t SET family_started_at =
    (SELECT min(x.created_at) FROM refresh_tokens x WHERE x.family_id = t.family_id);

ALTER TABLE refresh_tokens ALTER COLUMN family_started_at SET NOT NULL;
