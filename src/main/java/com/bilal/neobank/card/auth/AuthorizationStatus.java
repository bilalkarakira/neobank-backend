package com.bilal.neobank.card.auth;

public enum AuthorizationStatus {
    /** Approved: holds money until it is reversed, expires or is cleared. */
    AUTHORIZED,
    DECLINED,
    REVERSED,
    EXPIRED,
    /** Cleared and posted to the ledger (set by clearing, a later phase). */
    CAPTURED
}
