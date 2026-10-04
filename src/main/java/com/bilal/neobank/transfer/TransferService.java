package com.bilal.neobank.transfer;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountService;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.account.IbanGenerator;
import com.bilal.neobank.category.CategoryNotFoundException;
import com.bilal.neobank.category.CategoryRepository;
import com.bilal.neobank.ledger.Fingerprint;
import com.bilal.neobank.ledger.IdempotentTransactions;
import com.bilal.neobank.ledger.LedgerEntryRepository;
import com.bilal.neobank.ledger.LedgerTransaction;
import com.bilal.neobank.transfer.TransferExceptions.InvalidIbanException;
import com.bilal.neobank.transfer.TransferExceptions.RecipientNotFoundException;
import com.bilal.neobank.transfer.TransferExceptions.RecipientUnavailableException;
import com.bilal.neobank.transfer.TransferExceptions.SameAccountException;
import com.bilal.neobank.transfer.dto.RecipientResponse;
import com.bilal.neobank.transfer.dto.TransferRequest;
import com.bilal.neobank.transfer.dto.TransferResponse;
import com.bilal.neobank.user.User;
import com.bilal.neobank.user.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Validates a transfer request, then runs it idempotently. Not @Transactional itself (see IdempotentTransactions). */
@Service
public class TransferService {

    public record Result(TransferResponse response, boolean replayed) {
    }

    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository entryRepository;
    private final IdempotentTransactions idempotent;
    private final TransferExecutor executor;
    private final CategoryRepository categoryRepository;

    public TransferService(AccountService accountService, AccountRepository accountRepository,
            UserRepository userRepository, LedgerEntryRepository entryRepository, IdempotentTransactions idempotent,
            TransferExecutor executor, CategoryRepository categoryRepository) {
        this.accountService = accountService;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.entryRepository = entryRepository;
        this.idempotent = idempotent;
        this.executor = executor;
        this.categoryRepository = categoryRepository;
    }

    public Result transfer(UUID userId, String idempotencyKey, TransferRequest request) {
        IdempotentTransactions.requireValidKey(idempotencyKey);
        String iban = IbanGenerator.normalize(request.destinationIban());
        if (!IbanGenerator.isValid(iban)) {
            throw new InvalidIbanException();
        }

        Account source = accountService.get(userId, request.sourceAccountId()); // 404 unless the caller owns it
        Account destination = findRecipient(iban);
        if (destination.getId().equals(source.getId())) {
            throw new SameAccountException();
        }

        BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        String description = request.description() == null || request.description().isBlank()
                ? null : request.description().trim();
        UUID categoryId = request.categoryId();
        if (categoryId != null && !categoryRepository.existsById(categoryId)) {
            throw new CategoryNotFoundException();
        }
        // the category is part of the request, so changing it under the same key is a different request
        String hash = Fingerprint.sha256(source.getId() + "|" + iban + "|" + amount.toPlainString() + "|"
                + (description == null ? "" : description) + "|" + (categoryId == null ? "" : categoryId));

        IdempotentTransactions.Outcome outcome = idempotent.execute(userId, idempotencyKey, hash,
                () -> executor.execute(userId, source.getId(), destination.getId(), amount, description,
                        idempotencyKey, hash, categoryId));
        return new Result(toResponse(outcome.transaction()), outcome.replayed());
    }

    /** For the "is this who you mean?" confirmation: first name and last initial only. */
    public RecipientResponse lookup(String rawIban) {
        String iban = IbanGenerator.normalize(rawIban);
        if (!IbanGenerator.isValid(iban)) {
            throw new InvalidIbanException();
        }
        Account account = findRecipient(iban);
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new RecipientUnavailableException();
        }
        User owner = userRepository.findById(account.getUserId()).orElseThrow(RecipientNotFoundException::new);
        String initial = owner.getLastName().isEmpty() ? "" : " " + owner.getLastName().charAt(0) + ".";
        return new RecipientResponse(account.getIban(), owner.getFirstName() + initial);
    }

    /** Bank-internal accounts are never valid recipients, and are indistinguishable from "no such IBAN". */
    private Account findRecipient(String iban) {
        return accountRepository.findByIban(iban)
                .filter(account -> account.getType() != AccountType.SYSTEM)
                .orElseThrow(RecipientNotFoundException::new);
    }

    private TransferResponse toResponse(LedgerTransaction transaction) {
        Account destination = accountRepository.findById(transaction.getDestinationAccountId()).orElseThrow();
        BigDecimal balanceAfter = entryRepository
                .findByTransactionIdAndAccountId(transaction.getId(), transaction.getSourceAccountId())
                .orElseThrow(IllegalStateException::new).getBalanceAfter();
        return new TransferResponse(transaction.getId(), transaction.getSourceAccountId(), destination.getIban(),
                money(transaction.getAmount()), money(balanceAfter), transaction.getCreatedAt());
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
