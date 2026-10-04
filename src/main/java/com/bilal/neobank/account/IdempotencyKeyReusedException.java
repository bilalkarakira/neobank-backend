package com.bilal.neobank.account;

/** The same Idempotency-Key was sent with a different request: a client bug, never silently accepted. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("Idempotency key was already used for a different request");
    }
}
