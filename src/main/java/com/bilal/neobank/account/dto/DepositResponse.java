package com.bilal.neobank.account.dto;

import java.time.Instant;
import java.util.UUID;

/** Amounts are strings (see AccountResponse). {@code balance} is the balance right after this deposit. */
public record DepositResponse(UUID transactionId, UUID accountId, String amount, String balance, Instant createdAt) {
}
