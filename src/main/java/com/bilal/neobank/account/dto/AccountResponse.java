package com.bilal.neobank.account.dto;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * The balance is a string so JSON consumers never round money through floating point. {@code interestRate} is the
 * yearly rate (APY) as a percentage like "3.50" for savings accounts, and null for every other type.
 */
public record AccountResponse(UUID id, String iban, AccountType type, String currency, AccountStatus status,
        String nickname, String balance, String availableBalance, String heldAmount, String interestRate,
        Instant createdAt) {

    /** {@code held} is the money reserved by card authorizations; available = balance minus held. */
    public static AccountResponse from(Account account, String savingsApyPercent, BigDecimal held) {
        return new AccountResponse(account.getId(), account.getIban(), account.getType(), account.getCurrency(),
                account.getStatus(), account.getNickname(),
                account.getBalance().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                account.getBalance().subtract(held).setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                held.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                account.getType() == AccountType.SAVINGS ? savingsApyPercent : null, account.getCreatedAt());
    }
}
