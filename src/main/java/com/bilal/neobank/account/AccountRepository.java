package com.bilal.neobank.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Scoped by owner on purpose: callers can never load an account they do not own. */
    Optional<Account> findByIdAndUserId(UUID id, UUID userId);

    Optional<Account> findByIban(String iban);

    long countByUserIdAndTypeAndStatusNot(UUID userId, AccountType type, AccountStatus status);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumber();

    /**
     * Locks the rows (SELECT ... FOR UPDATE) in ascending id order. Every transaction that locks several
     * accounts must use this order, otherwise two opposite transfers could deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllOrderedById(@Param("ids") Collection<UUID> ids);
}
