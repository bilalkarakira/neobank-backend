package com.bilal.neobank.admin;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountNotFoundException;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.admin.AdminDtos.AccountCounts;
import com.bilal.neobank.admin.AdminDtos.AccountPage;
import com.bilal.neobank.admin.AdminDtos.AccountRow;
import com.bilal.neobank.admin.AdminDtos.AuditEntry;
import com.bilal.neobank.admin.AdminDtos.Overview;
import com.bilal.neobank.admin.AdminDtos.Reconciliation;
import com.bilal.neobank.admin.AdminDtos.RecurringHealth;
import com.bilal.neobank.admin.AdminDtos.Volume;
import com.bilal.neobank.interest.InterestService;
import com.bilal.neobank.ledger.LedgerReconciliationService;
import com.bilal.neobank.recurring.RecurringExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What an administrator can see and do. Every state-changing action is audited in the same transaction. */
@Service
public class AdminService {

    public static final int MAX_PAGE_SIZE = 50;
    private static final int AUDIT_LIMIT = 100;

    private final AdminRepository repository;
    private final AccountRepository accountRepository;
    private final LedgerReconciliationService reconciliationService;
    private final InterestService interestService;
    private final RecurringExecutor recurringExecutor;
    private final Clock clock;

    public AdminService(AdminRepository repository, AccountRepository accountRepository,
            LedgerReconciliationService reconciliationService, InterestService interestService,
            RecurringExecutor recurringExecutor, Clock clock) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.reconciliationService = reconciliationService;
        this.interestService = interestService;
        this.recurringExecutor = recurringExecutor;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        OffsetDateTime now = now();
        Map<AccountStatus, Long> counts = repository.accountCountsByStatus();
        AdminRepository.VolumeRow day = repository.transfersSince(now.minus(Duration.ofHours(24)));
        AdminRepository.VolumeRow month = repository.transfersSince(now.minus(Duration.ofDays(30)));
        return new Overview(now.toInstant(), repository.customerCount(),
                new AccountCounts(counts.get(AccountStatus.ACTIVE), counts.get(AccountStatus.FROZEN),
                        counts.get(AccountStatus.CLOSED)),
                money(repository.depositsHeld()), new Volume(day.count(), money(day.amount())),
                new Volume(month.count(), money(month.amount())), money(repository.interestPaid()),
                new RecurringHealth(repository.recurringCount("ACTIVE"), repository.recurringCount("PAUSED"),
                        repository.failedRecurringRunsSince(now.minus(Duration.ofDays(7)))));
    }

    @Transactional(readOnly = true)
    public Reconciliation reconciliation() {
        LedgerReconciliationService.Report report = reconciliationService.check();
        return new Reconciliation(report.isHealthy(), money(report.totalOfAllEntries()),
                report.accountsWithWrongCachedBalance(), report.customerAccountsBelowZero());
    }

    @Transactional(readOnly = true)
    public AccountPage accounts(String query, AccountStatus status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        List<AccountRow> items = repository.searchAccounts(query, status, safeSize, safePage * safeSize);
        return new AccountPage(items, repository.countAccounts(query, status), safePage, safeSize);
    }

    @Transactional
    public void freeze(UUID adminId, UUID accountId, String reason) {
        changeStatus(adminId, accountId, reason, AccountStatus.ACTIVE, AccountStatus.FROZEN, "FREEZE_ACCOUNT");
    }

    @Transactional
    public void unfreeze(UUID adminId, UUID accountId, String reason) {
        changeStatus(adminId, accountId, reason, AccountStatus.FROZEN, AccountStatus.ACTIVE, "UNFREEZE_ACCOUNT");
    }

    private void changeStatus(UUID adminId, UUID accountId, String reason, AccountStatus from, AccountStatus to,
            String action) {
        // the row lock serialises with any money movement touching this account: a freeze takes effect cleanly
        Account account = accountRepository.lockAllOrderedById(List.of(accountId)).stream().findFirst()
                .filter(a -> a.getType() != AccountType.SYSTEM) // bank-internal accounts are invisible here
                .orElseThrow(AccountNotFoundException::new);
        if (account.getStatus() != from) {
            throw new AdminExceptions.InvalidStateException("The account is %s, so it cannot be %s."
                    .formatted(account.getStatus(), to == AccountStatus.FROZEN ? "frozen" : "unfrozen"));
        }
        account.changeStatus(to);
        accountRepository.save(account);
        repository.insertAction(adminId, action, accountId, reason.trim(), null, now());
    }

    /** Runs the monthly interest job now. Safe to repeat: months already accrued are skipped. */
    public InterestService.RunSummary runInterest(UUID adminId) {
        InterestService.RunSummary summary = interestService.accrueAllDue();
        repository.insertAction(adminId, "RUN_INTEREST", null, null,
                "credited=%d skipped=%d failed=%d%s".formatted(summary.credited(), summary.skipped(),
                        summary.failed(), summary.skippedByLock() ? " (another run was in progress)" : ""), now());
        return summary;
    }

    /** Runs the due standing orders now. Safe to repeat: every date moves money at most once. */
    public RecurringExecutor.RunSummary runRecurring(UUID adminId) {
        RecurringExecutor.RunSummary summary = recurringExecutor.runAllDue();
        repository.insertAction(adminId, "RUN_RECURRING", null, null,
                "succeeded=%d failed=%d%s".formatted(summary.succeeded(), summary.failed(),
                        summary.skippedByLock() ? " (another run was in progress)" : ""), now());
        return summary;
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> audit() {
        return repository.recentActions(AUDIT_LIMIT);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
