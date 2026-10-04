package com.bilal.neobank.account;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("The Idempotency-Key header is required (8-64 characters: letters, digits, '-' or '_')");
    }
}
