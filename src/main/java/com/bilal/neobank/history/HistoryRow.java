package com.bilal.neobank.history;

import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.ledger.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One ledger entry of the inspected account, joined with its transaction and the counterparty account. */
public record HistoryRow(UUID transactionId, TransactionType type, BigDecimal amount, BigDecimal balanceAfter,
        String description, Instant createdAt, AccountType counterpartyType, UUID counterpartyUserId,
        String counterpartyIban, String counterpartyNickname, String counterpartyFirstName,
        String counterpartyLastName, String categoryName) {
}
