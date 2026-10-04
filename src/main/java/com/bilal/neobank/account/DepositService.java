package com.bilal.neobank.account;

import com.bilal.neobank.account.dto.DepositRequest;
import com.bilal.neobank.account.dto.DepositResponse;
import com.bilal.neobank.ledger.LedgerEntry;
import com.bilal.neobank.ledger.LedgerEntryRepository;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.LedgerTransaction;
import com.bilal.neobank.ledger.LedgerTransactionRepository;
import com.bilal.neobank.ledger.Posting;
import com.bilal.neobank.ledger.SystemAccounts;
import com.bilal.neobank.ledger.TransactionType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deposits "fake funding" into a customer account: the bank funding account is debited and the customer account
 * credited. Deliberately NOT @Transactional: when two identical requests race, the loser's ledger transaction
 * rolls back on the unique key, and only then can this method read the winner's committed result.
 */
@Service
public class DepositService {

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    public record Result(DepositResponse response, boolean replayed) {
    }

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    public DepositService(AccountService accountService, LedgerService ledgerService,
            LedgerTransactionRepository transactionRepository, LedgerEntryRepository entryRepository) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    public Result deposit(UUID userId, UUID accountId, String idempotencyKey, DepositRequest request) {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        accountService.get(userId, accountId); // 404 unless the caller owns the account

        BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        String description = request.description() == null || request.description().isBlank()
                ? null : request.description().trim();
        String hash = fingerprint(accountId, amount, description);

        var existing = transactionRepository.findByInitiatedByAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, accountId);
        }

        try {
            LedgerTransaction transaction = ledgerService.post(TransactionType.DEPOSIT, userId, idempotencyKey, hash,
                    description, List.of(new Posting(SystemAccounts.FUNDING, amount.negate()),
                            new Posting(accountId, amount)));
            return new Result(toResponse(transaction, accountId), false);
        } catch (DataIntegrityViolationException e) {
            // An identical request committed first (unique key violated): answer with its result instead.
            return transactionRepository.findByInitiatedByAndIdempotencyKey(userId, idempotencyKey)
                    .map(winner -> replay(winner, hash, accountId))
                    .orElseThrow(() -> e);
        }
    }

    private Result replay(LedgerTransaction original, String hash, UUID accountId) {
        if (!original.getRequestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException();
        }
        return new Result(toResponse(original, accountId), true);
    }

    private DepositResponse toResponse(LedgerTransaction transaction, UUID accountId) {
        LedgerEntry entry = entryRepository.findByTransactionIdAndAccountId(transaction.getId(), accountId)
                .orElseThrow(IllegalStateException::new);
        return new DepositResponse(transaction.getId(), accountId, money(entry.getAmount()),
                money(entry.getBalanceAfter()), transaction.getCreatedAt());
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String fingerprint(UUID accountId, BigDecimal amount, String description) {
        String canonical = accountId + "|" + amount.toPlainString() + "|" + (description == null ? "" : description);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
