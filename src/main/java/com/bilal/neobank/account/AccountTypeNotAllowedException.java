package com.bilal.neobank.account;

public class AccountTypeNotAllowedException extends RuntimeException {

    public AccountTypeNotAllowedException(AccountType type) {
        super("Accounts of type " + type + " cannot be opened");
    }
}
