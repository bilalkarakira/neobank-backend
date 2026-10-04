package com.bilal.neobank.ledger;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountNotActiveException;
import com.bilal.neobank.account.AccountNotFoundException;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only code that moves money. Every call writes one transaction with balanced entries and updates the
 * cached balances in the same database transaction, so the two can never drift apart.
 */
@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    public LedgerService(AccountRepository accountRepository, LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    @Transactional
    public LedgerTransaction post(TransactionType type, UUID initiatedBy, String idempotencyKey, String requestHash,
            String description, List<Posting> postings) {
        requireBalanced(postings);

        Set<UUID> ids = postings.stream().map(Posting::accountId).collect(Collectors.toSet());
        // lock in id order (see AccountRepository) so concurrent multi-account postings cannot deadlock
        Map<UUID, Account> accounts = accountRepository.lockAllOrderedById(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        if (accounts.size() != ids.size()) {
            throw new AccountNotFoundException();
        }

        LedgerTransaction transaction = transactionRepository.saveAndFlush(
                new LedgerTransaction(type, initiatedBy, idempotencyKey, requestHash, description));

        for (Posting posting : postings) {
            Account account = accounts.get(posting.accountId());
            if (account.getStatus() != AccountStatus.ACTIVE) {
                throw new AccountNotActiveException();
            }
            account.applyDelta(posting.amount());
            if (account.getType() != AccountType.SYSTEM && account.getBalance().signum() < 0) {
                throw new InsufficientFundsException();
            }
            entryRepository.save(
                    new LedgerEntry(transaction.getId(), account.getId(), posting.amount(), account.getBalance()));
        }
        return transaction;
    }

    private static void requireBalanced(List<Posting> postings) {
        Set<UUID> distinct = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Posting posting : postings) {
            if (posting.amount().signum() == 0) {
                throw new UnbalancedPostingException("A posting must not be zero");
            }
            distinct.add(posting.accountId());
            sum = sum.add(posting.amount());
        }
        if (postings.size() < 2 || distinct.size() != postings.size()) {
            throw new UnbalancedPostingException("A transaction needs at least two different accounts");
        }
        if (sum.signum() != 0) {
            throw new UnbalancedPostingException("Postings must sum to zero but sum to " + sum);
        }
    }
}
