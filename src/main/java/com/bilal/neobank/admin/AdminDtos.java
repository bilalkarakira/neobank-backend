package com.bilal.neobank.admin;

import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.ledger.TransactionType;
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

    /** Customers currently locked out of login, and users currently blocked for probing IBANs. */
    public record SecurityCounts(long lockedLogins, long probingBlocks) {
    }

    public record Overview(Instant generatedAt, long customers, AccountCounts accounts, String depositsHeld,
            Volume transfersLast24Hours, Volume transfersLast30Days, String interestPaid, RecurringHealth recurring,
            SecurityCounts security) {
    }

    /** A customer who cannot log in right now because of too many wrong passwords. */
    public record LockedLogin(String email, int failures, Instant lockedUntil) {
    }

    /** A user who cannot look up or send to IBANs right now because of too many unknown ones. */
    public record ProbingBlock(UUID userId, String email, int misses, Instant blockedUntil) {
    }

    /**
     * @param otherLockedAddresses locked addresses that belong to no customer (typos, scanners): nobody is harmed by
     *                             those locks, and their hashes are never exposed
     */
    public record SecuritySnapshot(List<LockedLogin> lockedLogins, long otherLockedAddresses,
            List<ProbingBlock> probingBlocks) {
    }

    public record UnlockLoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String reason) {
    }

    public record Reconciliation(boolean healthy, String totalOfAllEntries, List<UUID> accountsWithWrongBalance,
            List<UUID> customerAccountsBelowZero) {
    }

    public record AccountRow(UUID id, String iban, AccountType type, AccountStatus status, String balance,
            String ownerEmail, String ownerName, Instant createdAt, UUID ownerId) {
    }

    public record CustomerProfile(UUID id, String firstName, String lastName, String email, Instant createdAt) {
    }

    public record CustomerAccount(UUID id, String iban, AccountType type, AccountStatus status, String nickname,
            String balance, Instant createdAt) {
    }

    /** One ledger entry of one of the customer's accounts; amount is signed (money out is negative). */
    public record CustomerActivity(Instant at, String accountIban, TransactionType type, String amount,
            String balanceAfter, String description) {
    }

    /**
     * Everything support needs to understand a customer's situation on one screen.
     *
     * @param loginLockedUntil null when the customer can log in
     */
    public record CustomerDetail(CustomerProfile profile, List<CustomerAccount> accounts,
            List<CustomerActivity> recentActivity, int activeSessions, Instant loginLockedUntil, long activeOrders,
            long pausedOrders) {
    }

    public record AccountPage(List<AccountRow> items, long total, int page, int size) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 200) String reason) {
    }

    public record AuditEntry(long id, Instant at, String adminEmail, String action, String accountIban,
            String reason, String detail) {
    }
}
