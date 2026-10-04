package com.bilal.neobank.history.dto;

import com.bilal.neobank.history.Direction;
import com.bilal.neobank.ledger.TransactionType;

import java.time.Instant;
import java.util.UUID;

/** Amounts are signed strings: money out is "-250.00", money in is "250.00". */
public record TransactionItem(UUID transactionId, TransactionType type, Direction direction, String amount,
        String balanceAfter, String description, Counterparty counterparty, Instant createdAt) {

    /** Deliberately minimal: a label, the IBAN (as on a bank statement) and whether it is the user's own account. */
    public record Counterparty(String label, String iban, boolean ownAccount) {
    }
}
