-- Online payments (P6d): the security code printed on the card (CVV2). Only a keyed hash is kept, like the PIN; the code itself is shown once, when the
-- card is issued. Cards issued before this have none and are refused for online payments.

ALTER TABLE cards ADD COLUMN cvv_hash VARCHAR(64);
