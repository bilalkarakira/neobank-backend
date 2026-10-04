package com.bilal.neobank.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Scoped by owner on purpose: callers can never load an account they do not own. */
    Optional<Account> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndTypeAndStatusNot(UUID userId, AccountType type, AccountStatus status);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumber();
}
