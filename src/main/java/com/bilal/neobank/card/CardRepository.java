package com.bilal.neobank.card;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CardRepository extends JpaRepository<Card, UUID> {

    List<Card> findByUserIdAndStatusNotOrderByCreatedAtDesc(UUID userId, CardStatus status);

    long countByUserIdAndStatusNot(UUID userId, CardStatus status);

    boolean existsByPanHash(String panHash);

    Optional<Card> findByPanHash(String panHash);

    /** Scoped by owner on purpose; a cancelled card is gone as far as the customer is concerned. Row lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.id = :id and c.userId = :userId and c.status <> 'CANCELLED'")
    Optional<Card> lockOwnedNotCancelled(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("select c from Card c where c.id = :id and c.userId = :userId and c.status <> 'CANCELLED'")
    Optional<Card> findOwnedNotCancelled(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Row lock; the card engine takes it AFTER the account lock (lock order: account first, then card). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.id = :id")
    Optional<Card> lockById(@Param("id") UUID id);

    /** Closing an account ends its cards. Call it after the account row is locked. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Card c set c.status = 'CANCELLED', c.freezeReason = null, c.cancelledAt = :now where c.accountId = :accountId and c.status <> 'CANCELLED'")
    int cancelAllOfAccount(@Param("accountId") UUID accountId, @Param("now") Instant now);
}
