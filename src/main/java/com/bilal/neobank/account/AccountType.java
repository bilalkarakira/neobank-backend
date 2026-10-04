package com.bilal.neobank.account;

public enum AccountType {
    CHECKING,
    SAVINGS,
    /** Internal bank accounts (e.g. funding). Never opened by customers. */
    SYSTEM
}
