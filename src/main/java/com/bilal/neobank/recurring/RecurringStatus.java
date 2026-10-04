package com.bilal.neobank.recurring;

public enum RecurringStatus {
    ACTIVE,
    PAUSED,
    /** The end date has passed. */
    COMPLETED,
    /** Cancelled by the owner; hidden from the API afterwards. */
    CANCELLED
}
