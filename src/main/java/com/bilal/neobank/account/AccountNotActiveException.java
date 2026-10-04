package com.bilal.neobank.account;

public class AccountNotActiveException extends RuntimeException {

    public AccountNotActiveException() {
        super("Account is not active");
    }
}
