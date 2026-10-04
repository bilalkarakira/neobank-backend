package com.bilal.neobank.recurring.dto;

import com.bilal.neobank.recurring.Frequency;
import com.bilal.neobank.recurring.RecurringStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class RecurringDtos {

    private RecurringDtos() {
    }

    public record CreateRequest(
            @NotNull UUID sourceAccountId,
            @NotBlank @Size(max = 40) String destinationIban,
            @NotNull
            @DecimalMin(value = "0.01", message = "must be at least 0.01")
            @DecimalMax(value = "10000.00", message = "must be at most 10000.00")
            @Digits(integer = 5, fraction = 2, message = "must have at most 2 decimal places")
            BigDecimal amount,
            @Size(max = 100) String description,
            UUID categoryId,
            @NotNull Frequency frequency,
            /** First run date (UTC calendar day), today or later. */
            @NotNull LocalDate startDate,
            /** Optional: no run happens after this date. */
            LocalDate endDate) {
    }

    /** What can change after creation. The schedule and the accounts are fixed: cancel and create a new order. */
    public record UpdateRequest(
            @NotNull
            @DecimalMin(value = "0.01", message = "must be at least 0.01")
            @DecimalMax(value = "10000.00", message = "must be at most 10000.00")
            @Digits(integer = 5, fraction = 2, message = "must have at most 2 decimal places")
            BigDecimal amount,
            @Size(max = 100) String description,
            UUID categoryId) {
    }

    public record PaymentResponse(UUID id, UUID sourceAccountId, String destinationIban, String amount,
            String description, UUID categoryId, Frequency frequency, LocalDate startDate, LocalDate endDate,
            LocalDate nextRunDate, RecurringStatus status, boolean autoPaused, int consecutiveFailures,
            Instant createdAt) {
    }

    public record RunResponse(LocalDate runDate, String status, String failureCode, String failureMessage,
            UUID transactionId, Instant executedAt) {
    }
}
