-- Refresh tokens: long-lived, single-use credentials that are exchanged for a new short-lived access token.
-- Only the SHA-256 hash is stored, so a database leak does not hand out working sessions.
--
-- A "family" is one login. Every refresh retires the presented token (rotated_at) and creates a child in the same
-- family. Presenting an already-retired token means two parties hold the same token (theft or a replayed request),
-- so the whole family is revoked and that login must start over.
CREATE TABLE refresh_tokens (
    id                UUID        PRIMARY KEY,
    user_id           UUID        NOT NULL REFERENCES users (id),
    family_id         UUID        NOT NULL,
    token_hash        CHAR(64)    NOT NULL UNIQUE,
    created_at        TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,   -- sliding: pushed forward by each rotation, never past family_expires_at
    family_expires_at TIMESTAMPTZ NOT NULL,   -- absolute: a login cannot be extended beyond this, however active
    rotated_at        TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
