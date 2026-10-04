package com.bilal.neobank.admin;

import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the admin API. Money is always a two-decimal string. */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record AccountCounts(long active, long frozen, long closed) {
    }

    /** Number and total amount of transfers in a time window. */
    public record Volume(long count, String amount) {
    }

    public record RecurringHealth(long active, long paused, long failedRunsLast7Days) {
    }

    public record Overview(Instant generatedAt, long customers, AccountCounts accounts, String depositsHeld,
            Volume transfersLast24Hours, Volume transfersLast30Days, String interestPaid, RecurringHealth recurring) {
    }

    public record Reconciliation(boolean healthy, String totalOfAllEntries, List<UUID> accountsWithWrongBalance,
            List<UUID> customerAccountsBelowZero) {
    }

    public record AccountRow(UUID id, String iban, AccountType type, AccountStatus status, String balance,
            String ownerEmail, String ownerName, Instant createdAt) {
    }

    public record AccountPage(List<AccountRow> items, long total, int page, int size) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 200) String reason) {
    }

    public record AuditEntry(long id, Instant at, String adminEmail, String action, String accountIban,
            String reason, String detail) {
    }
}
