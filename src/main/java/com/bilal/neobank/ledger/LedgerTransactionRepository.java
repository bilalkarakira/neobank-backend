package com.bilal.neobank.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByInitiatedByAndIdempotencyKey(UUID initiatedBy, String idempotencyKey);

    /** Total the user sent since {@code since} to accounts that belong to somebody else. */
    @Query("""
            select coalesce(sum(t.amount), 0) from LedgerTransaction t
            where t.type = :type and t.initiatedBy = :userId and t.createdAt >= :since
              and t.destinationAccountId not in (select a.id from Account a where a.userId = :userId)
            """)
    BigDecimal sumSentToOthersSince(@Param("type") TransactionType type, @Param("userId") UUID userId,
            @Param("since") Instant since);
}
