-- A payment the card network approved on its own while NeoBank was unavailable, and told NeoBank about afterwards (0120).
ALTER TABLE authorizations ADD COLUMN via_stand_in BOOLEAN NOT NULL DEFAULT FALSE;
