package com.bilal.neobank.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByInitiatedByAndIdempotencyKey(UUID initiatedBy, String idempotencyKey);
}
