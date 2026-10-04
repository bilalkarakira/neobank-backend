package com.bilal.neobank.account;

public class AccountLimitReachedException extends RuntimeException {

    public AccountLimitReachedException(AccountType type, int max) {
        super("You can have at most %d open %s account%s.".formatted(max, type.name().toLowerCase(), max == 1 ? "" : "s"));
    }
}
