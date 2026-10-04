package com.bilal.neobank.transfer.dto;

import java.time.Instant;
import java.util.UUID;

/** {@code balance} is the SOURCE account's balance right after the transfer. */
public record TransferResponse(UUID transactionId, UUID sourceAccountId, String destinationIban, String amount,
        String balance, Instant createdAt) {
}
