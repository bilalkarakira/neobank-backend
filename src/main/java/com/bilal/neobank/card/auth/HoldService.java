package com.bilal.neobank.card.auth;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountNotActiveException;
import com.bilal.neobank.account.AccountNotFoundException;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.ledger.HoldsProvider;
import com.bilal.neobank.ledger.InsufficientFundsException;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reserves money on an account. This is the card-side twin of {@code LedgerService.post}: both take the account's row
 * lock first and only then read the balance and the holds, so a transfer and an authorization can never spend the same
 * money. (Postgres READ COMMITTED: every statement after the lock sees everything committed before it.)
 */
@Service
public class HoldService {

    private final AccountRepository accountRepository;
    private final AuthorizationRepository authorizationRepository;
    private final HoldsProvider holds;
    private final EntityManager entityManager;

    public HoldService(AccountRepository accountRepository, AuthorizationRepository authorizationRepository,
            HoldsProvider holds, EntityManager entityManager) {
        this.accountRepository = accountRepository;
        this.authorizationRepository = authorizationRepository;
        this.holds = holds;
        this.entityManager = entityManager;
    }

    /**
     * Locks an account row and re-reads it, so the caller decides on the balance as it is now. Locking again in the same
     * transaction is harmless. Lock order for the whole application: the account row first, then any card row.
     */
    public Account lockAccount(java.util.UUID accountId) {
        Account account = accountRepository.lockAllOrderedById(List.of(accountId)).stream().findFirst()
                .orElseThrow(AccountNotFoundException::new);
        entityManager.refresh(account); // see LedgerService: never trust a balance loaded before the lock
        return account;
    }

    /**
     * Saves an approved authorization if the account can afford it and says whether it did. Returning false (rather
     * than throwing) matters: an exception passing through a transactional method would mark the caller's transaction
     * rollback-only, and a decline must still be recorded.
     */
    @Transactional
    public boolean tryReserve(Authorization approved) {
        Account account = lockAccount(approved.getAccountId());
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }
        if (account.getBalance().subtract(holds.activeHolds(account.getId())).compareTo(approved.getAmount()) < 0) {
            return false;
        }
        authorizationRepository.saveAndFlush(approved);
        return true;
    }

    /** Same, but refuses with an exception. Used where there is no decline to record. */
    @Transactional
    public Authorization reserve(Authorization approved) {
        if (!tryReserve(approved)) {
            throw new InsufficientFundsException();
        }
        return approved;
    }
}
