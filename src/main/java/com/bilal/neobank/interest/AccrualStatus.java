package com.bilal.neobank.interest;

public enum AccrualStatus {
    /** Interest was computed and credited to the account. */
    CREDITED,
    /** The month's interest rounded to less than one cent: recorded, but no money moved. */
    SKIPPED_ZERO,
    /** The account was not active when the month was processed: no interest, and no catch-up later. */
    SKIPPED_INACTIVE
}
