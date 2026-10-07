-- cardnet database, part 8: online (card not present) payments at the acquirer (P6d). The payment record says it was made online;
-- the security code (CVV2) is never kept, only used to build the request that is sent.

ALTER TABLE acq_transactions ADD COLUMN online BOOLEAN NOT NULL DEFAULT FALSE;
