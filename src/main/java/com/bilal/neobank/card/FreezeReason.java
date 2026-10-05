package com.bilal.neobank.card;

public enum FreezeReason {
    /** The customer froze it; the customer can unfreeze it. */
    CUSTOMER,
    /** Too many wrong PINs; the customer can unfreeze it. */
    PIN_TRIES,
    /** The bank froze it (reserved: no admin card action exists yet); the customer cannot unfreeze it. */
    ADMIN
}
