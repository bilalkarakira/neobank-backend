package com.bilal.neobank.transfer;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountNotActiveException;
import com.bilal.neobank.account.AccountNotFoundException;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.LedgerTransaction;
import com.bilal.neobank.ledger.Posting;
import com.bilal.neobank.ledger.TransactionType;
import com.bilal.neobank.transfer.TransferExceptions.DailyLimitExceededException;
import com.bilal.neobank.transfer.TransferExceptions.RecipientUnavailableException;
import com.bilal.neobank.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The database-transactional core of a transfer. Lock order is always: (1) the sender's user row, which serializes
 * that user's daily-limit checks, then (2) the two account rows in ascending id order (inside LedgerService).
 * Using one fixed order everywhere is what prevents deadlocks between opposite transfers.
 */
@Service
public class TransferExecutor {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final TransferLimitService limits;

    public TransferExecutor(UserRepository userRepository, AccountRepository accountRepository,
            LedgerService ledgerService, TransferLimitService limits) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.limits = limits;
    }

    @Transactional
    public LedgerTransaction execute(UUID userId, UUID sourceId, UUID destinationId, BigDecimal amount,
            String description, String idempotencyKey, String requestHash) {
        userRepository.findByIdForUpdate(userId).orElseThrow(AccountNotFoundException::new);

        Account source = accountRepository.findById(sourceId).orElseThrow(AccountNotFoundException::new);
        Account destination = accountRepository.findById(destinationId).orElseThrow(AccountNotFoundException::new);
        if (source.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }
        if (destination.getStatus() != AccountStatus.ACTIVE) {
            throw new RecipientUnavailableException();
        }

        boolean toSomeoneElse = !userId.equals(destination.getUserId());
        if (toSomeoneElse) {
            BigDecimal used = limits.usedToday(userId);
            BigDecimal remaining = limits.dailyLimit().subtract(used).max(BigDecimal.ZERO);
            if (amount.compareTo(remaining) > 0) {
                throw new DailyLimitExceededException(limits.dailyLimit().setScale(2), remaining.setScale(2));
            }
        }

        return ledgerService.post(TransactionType.TRANSFER, userId, idempotencyKey, requestHash, description,
                List.of(new Posting(sourceId, amount.negate()), new Posting(destinationId, amount)));
    }
}
