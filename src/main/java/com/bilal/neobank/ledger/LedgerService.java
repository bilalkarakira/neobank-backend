package com.bilal.neobank.ledger;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountNotActiveException;
import com.bilal.neobank.account.AccountNotFoundException;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only code that moves money. Every call writes one transaction with two balanced entries (a debit and a credit)
 * and updates the cached balances in the same database transaction, so the two can never drift apart.
 */
@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final EntityManager entityManager;

    public LedgerService(AccountRepository accountRepository, LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository, EntityManager entityManager) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.entityManager = entityManager;
    }

    /** @param postings exactly one debit (negative) and one credit (positive) of the same size, on different accounts */
    @Transactional
    public LedgerTransaction post(TransactionType type, UUID initiatedBy, String idempotencyKey, String requestHash,
            String description, List<Posting> postings) {
        Posting[] pair = requireBalancedPair(postings);
        Posting debit = pair[0];
        Posting credit = pair[1];

        Set<UUID> ids = Set.of(debit.accountId(), credit.accountId());
        // lock in id order (see AccountRepository) so concurrent postings on the same accounts cannot deadlock
        Map<UUID, Account> accounts = accountRepository.lockAllOrderedById(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        if (accounts.size() != ids.size()) {
            throw new AccountNotFoundException();
        }
        // A caller may have loaded these accounts BEFORE we got the lock; Hibernate would keep that stale balance
        // and silently overwrite another transaction's update. Re-read the state we now exclusively own.
        accounts.values().forEach(entityManager::refresh);

        LedgerTransaction transaction = transactionRepository.saveAndFlush(new LedgerTransaction(type, initiatedBy,
                idempotencyKey, requestHash, description, debit.accountId(), credit.accountId(), credit.amount()));

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

    /** Returns {debit, credit}. */
    private static Posting[] requireBalancedPair(List<Posting> postings) {
        if (postings.size() != 2) {
            throw new UnbalancedPostingException("A transaction needs exactly two postings");
        }
        Posting a = postings.get(0);
        Posting b = postings.get(1);
        if (a.amount().signum() == 0 || b.amount().signum() == 0) {
            throw new UnbalancedPostingException("A posting must not be zero");
        }
        if (a.accountId().equals(b.accountId())) {
            throw new UnbalancedPostingException("A transaction needs two different accounts");
        }
        BigDecimal sum = a.amount().add(b.amount());
        if (sum.signum() != 0) {
            throw new UnbalancedPostingException("Postings must sum to zero but sum to " + sum);
        }
        return a.amount().signum() < 0 ? new Posting[] { a, b } : new Posting[] { b, a };
    }
}
