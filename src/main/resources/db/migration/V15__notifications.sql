-- Security notices for a customer: a login from a device or address not seen before, a login lock, a password change.
-- This is the in-app inbox that stands in for an email channel (there is no mail server in this project): the rows are
-- what would be sent. The newest are shown to the customer, who can mark them read.
CREATE TABLE notifications (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       VARCHAR(30)  NOT NULL,   -- NEW_DEVICE_LOGIN | LOGIN_LOCKED | PASSWORD_CHANGED
    title      VARCHAR(100) NOT NULL,
    body       VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    read_at    TIMESTAMPTZ
);
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC, id DESC);
CREATE INDEX idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
