package com.bilal.neobank.transfer.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotBlank @Size(max = 40) String destinationIban,
        @NotNull
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @DecimalMax(value = "10000.00", message = "must be at most 10000.00")
        @Digits(integer = 5, fraction = 2, message = "must have at most 2 decimal places")
        BigDecimal amount,
        @Size(max = 100) String description) {
}
