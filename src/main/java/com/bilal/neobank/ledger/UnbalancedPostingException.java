package com.bilal.neobank.ledger;

/** A programming error (the ledger refuses to record unbalanced money movements), not a client error. */
public class UnbalancedPostingException extends IllegalStateException {

    public UnbalancedPostingException(String message) {
        super(message);
    }
}
