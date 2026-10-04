package com.bilal.neobank.account.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** More than two decimals is rejected, never rounded: silently changing a money amount is a classic bug. */
public record DepositRequest(
        @NotNull
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @DecimalMax(value = "10000.00", message = "must be at most 10000.00")
        @Digits(integer = 5, fraction = 2, message = "must have at most 2 decimal places")
        BigDecimal amount,
        @Size(max = 100) String description) {
}
