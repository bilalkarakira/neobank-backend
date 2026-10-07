package com.bilal.neobank.card.sca;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CardAuthenticationRepository extends JpaRepository<CardAuthentication, UUID> {

    Optional<CardAuthentication> findByReference(String reference);

    /** Waiting for the customer: pending and not expired, newest first. */
    @Query("""
            select a from CardAuthentication a
            where a.cardId in :cardIds and a.status = com.bilal.neobank.card.sca.CardAuthentication.Status.PENDING and a.expiresAt > :now
            order by a.createdAt desc
            """)
    List<CardAuthentication> pending(@Param("cardIds") Collection<UUID> cardIds, @Param("now") Instant now);

    /** The customer's answer: only while still pending and not expired. 1 when it took effect. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update CardAuthentication a set a.status = :status, a.decidedAt = :now
            where a.id = :id and a.status = com.bilal.neobank.card.sca.CardAuthentication.Status.PENDING and a.expiresAt > :now
            """)
    int decide(@Param("id") UUID id, @Param("status") CardAuthentication.Status status, @Param("now") Instant now);

    /** Uses an approval up: only if approved, unexpired and not used before, so one approval pays once. 1 when it took effect. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update CardAuthentication a set a.usedAt = :now
            where a.id = :id and a.status = com.bilal.neobank.card.sca.CardAuthentication.Status.APPROVED and a.usedAt is null
              and a.expiresAt > :now
            """)
    int markUsed(@Param("id") UUID id, @Param("now") Instant now);

    /** The payment it was used for did not go through (no funds, a limit): the approval is available again. */
    @Modifying(flushAutomatically = true)
    @Query("update CardAuthentication a set a.usedAt = null where a.id = :id")
    int release(@Param("id") UUID id);
}
