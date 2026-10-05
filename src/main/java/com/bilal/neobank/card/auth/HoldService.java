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
     * Saves an approved authorization if the account can afford it. Joins a surrounding transaction (the card engine
     * calls this after it has locked the account itself; locking again is harmless).
     *
     * @throws InsufficientFundsException when available balance (balance minus holds) is below the amount
     */
    @Transactional
    public Authorization reserve(Authorization approved) {
        Account account = accountRepository.lockAllOrderedById(List.of(approved.getAccountId())).stream().findFirst()
                .orElseThrow(AccountNotFoundException::new);
        entityManager.refresh(account); // see LedgerService: never trust a balance loaded before the lock
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }
        if (account.getBalance().subtract(holds.activeHolds(account.getId())).compareTo(approved.getAmount()) < 0) {
            throw new InsufficientFundsException();
        }
        return authorizationRepository.saveAndFlush(approved);
    }
}
