package com.bilal.neobank.account.dto;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** The balance is a string so JSON consumers never round money through floating point. */
public record AccountResponse(UUID id, String iban, AccountType type, String currency, AccountStatus status,
        String nickname, String balance, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getIban(), account.getType(), account.getCurrency(),
                account.getStatus(), account.getNickname(),
                account.getBalance().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), account.getCreatedAt());
    }
}
