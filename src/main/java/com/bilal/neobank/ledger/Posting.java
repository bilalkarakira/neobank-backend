package com.bilal.neobank.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/** One side of a transaction: credit (positive) or debit (negative) on an account. */
public record Posting(UUID accountId, BigDecimal amount) {
}
