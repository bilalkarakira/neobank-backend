package com.bilal.neobank.account;

import com.bilal.neobank.account.dto.DepositRequest;
import com.bilal.neobank.account.dto.DepositResponse;
import com.bilal.neobank.card.clearing.ReceivableCollection;
import com.bilal.neobank.ledger.Fingerprint;
import com.bilal.neobank.ledger.IdempotentTransactions;
import com.bilal.neobank.ledger.LedgerEntry;
import com.bilal.neobank.ledger.LedgerEntryRepository;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.LedgerTransaction;
import com.bilal.neobank.ledger.Posting;
import com.bilal.neobank.ledger.SystemAccounts;
import com.bilal.neobank.ledger.TransactionType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deposits "fake funding" into a customer account: the bank funding account is debited and the customer account
 * credited. Not @Transactional itself (see IdempotentTransactions).
 */
@Service
public class DepositService {

    public record Result(DepositResponse response, boolean replayed) {
    }

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final IdempotentTransactions idempotent;
    private final LedgerEntryRepository entryRepository;
    private final ReceivableCollection collection;

    public DepositService(AccountService accountService, LedgerService ledgerService,
            IdempotentTransactions idempotent, LedgerEntryRepository entryRepository, ReceivableCollection collection) {
        this.collection = collection;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.idempotent = idempotent;
        this.entryRepository = entryRepository;
    }

    public Result deposit(UUID userId, UUID accountId, String idempotencyKey, DepositRequest request) {
        IdempotentTransactions.requireValidKey(idempotencyKey);
        accountService.get(userId, accountId); // 404 unless the caller owns the account

        BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        String description = request.description() == null || request.description().isBlank()
                ? null : request.description().trim();
        String hash = Fingerprint.sha256(accountId + "|" + amount.toPlainString() + "|"
                + (description == null ? "" : description));

        AtomicReference<BigDecimal> collected = new AtomicReference<>(BigDecimal.ZERO);
        IdempotentTransactions.Outcome outcome = idempotent.execute(userId, idempotencyKey, hash, () -> {
            LedgerTransaction deposit = ledgerService.post(TransactionType.DEPOSIT, userId, idempotencyKey, hash, description,
                    List.of(new Posting(SystemAccounts.FUNDING, amount.negate()), new Posting(accountId, amount)));
            // money that arrives first pays off what the customer owes the bank from a cleared card payment (if anything)
            collected.set(collection.collectFrom(accountId));
            return deposit;
        });
        return new Result(toResponse(outcome.transaction(), accountId, collected.get()), outcome.replayed());
    }

    /** {@code collected} was taken from the new balance to pay a card shortfall, so the balance shown is after that. */
    private DepositResponse toResponse(LedgerTransaction transaction, UUID accountId, BigDecimal collected) {
        LedgerEntry entry = entryRepository.findByTransactionIdAndAccountId(transaction.getId(), accountId)
                .orElseThrow(IllegalStateException::new);
        return new DepositResponse(transaction.getId(), accountId, money(entry.getAmount()),
                money(entry.getBalanceAfter().subtract(collected)), transaction.getCreatedAt());
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
