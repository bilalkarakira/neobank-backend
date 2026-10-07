package com.bilal.neobank.card.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationRepository extends JpaRepository<Authorization, UUID> {

    /** Money reserved on an account right now: approved, not reversed, not past its expiry. */
    @Query("""
            select coalesce(sum(a.amount), 0) from Authorization a
            where a.accountId = :accountId and a.status = com.bilal.neobank.card.auth.AuthorizationStatus.AUTHORIZED
              and a.expiresAt > :now
            """)
    BigDecimal sumActiveHolds(@Param("accountId") UUID accountId, @Param("now") Instant now);

    /** Rows of [accountId, total]; accounts without holds do not appear. */
    @Query("""
            select a.accountId, sum(a.amount) from Authorization a
            where a.accountId in :accountIds and a.status = com.bilal.neobank.card.auth.AuthorizationStatus.AUTHORIZED
              and a.expiresAt > :now
            group by a.accountId
            """)
    List<Object[]> sumActiveHoldsByAccount(@Param("accountIds") Collection<UUID> accountIds,
            @Param("now") Instant now);

    /** What a card has approved since {@code since} and not given back (counts toward the daily limit). */
    @Query("""
            select coalesce(sum(a.amount), 0) from Authorization a
            where a.cardId = :cardId and a.createdAt >= :since
              and a.status in (com.bilal.neobank.card.auth.AuthorizationStatus.AUTHORIZED,
                               com.bilal.neobank.card.auth.AuthorizationStatus.CAPTURED)
            """)
    BigDecimal sumApprovedSince(@Param("cardId") UUID cardId, @Param("since") Instant since);

    /** The cash (processing code 01) a card has been approved for since {@code since}: counts toward the daily cash limit. */
    @Query("""
            select coalesce(sum(a.amount), 0) from Authorization a
            where a.cardId = :cardId and a.createdAt >= :since and a.processingCode like '01%'
              and a.status in (com.bilal.neobank.card.auth.AuthorizationStatus.AUTHORIZED,
                               com.bilal.neobank.card.auth.AuthorizationStatus.CAPTURED)
            """)
    BigDecimal sumCashApprovedSince(@Param("cardId") UUID cardId, @Param("since") Instant since);

    /** Approvals (response 00) of a card since {@code since}, whatever happened to them afterwards: the velocity count. */
    @Query("select count(a) from Authorization a where a.cardId = :cardId and a.responseCode = '00' and a.createdAt > :since")
    long countApprovalsSince(@Param("cardId") UUID cardId, @Param("since") Instant since);

    Page<Authorization> findByCardIdOrderByCreatedAtDesc(UUID cardId, Pageable pageable);

    @Query("""
            select a from Authorization a
            where a.cardId = :cardId and a.status = com.bilal.neobank.card.auth.AuthorizationStatus.AUTHORIZED
              and a.expiresAt > :now
            order by a.createdAt desc
            """)
    List<Authorization> findActiveHolds(@Param("cardId") UUID cardId, @Param("now") Instant now);

    @Query("""
            select count(a) from Authorization a where a.accountId = :accountId
              and a.status = com.bilal.neobank.card.auth.AuthorizationStatus.AUTHORIZED and a.expiresAt > :now
            """)
    long countActiveHolds(@Param("accountId") UUID accountId, @Param("now") Instant now);

    /** The request already decided with the same duplicate key (see the unique index of V16). */
    @Query("""
            select a from Authorization a where a.panHash = :panHash and a.acquirerId = :acquirerId and a.stan = :stan
              and a.rrn = :rrn and a.terminalId = :terminalId and a.localDate = :localDate
            """)
    Optional<Authorization> findDuplicate(@Param("panHash") String panHash, @Param("acquirerId") String acquirerId,
            @Param("stan") String stan, @Param("rrn") String rrn, @Param("terminalId") String terminalId,
            @Param("localDate") String localDate);

    /** The original a reversal points at (DE 90): same card, acquirer, STAN and transmission time. */
    Optional<Authorization> findFirstByPanHashAndAcquirerIdAndStanAndTransmittedAtOrderByCreatedAtAsc(String panHash,
            String acquirerId, String stan, String transmittedAt);

    /** How many increments of an authorization are in a given state. */
    long countByParentIdAndStatus(UUID parentId, AuthorizationStatus status);

    /** What the active increments of an authorization hold in all. */
    @Query("""
            select coalesce(sum(a.amount), 0) from Authorization a
            where a.parentId = :parentId and a.status = com.bilal.neobank.card.auth.AuthorizationStatus.AUTHORIZED
            """)
    BigDecimal sumActiveIncrements(@Param("parentId") UUID parentId);

    /** The first authorization was cleared for the total: its increments no longer hold anything. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Authorization a set a.status = com.bilal.neobank.card.auth.AuthorizationStatus.MERGED, a.releasedAt = :now
            where a.parentId = :parentId and a.status = com.bilal.neobank.card.auth.AuthorizationStatus.AUTHORIZED
            """)
    int mergeIncrements(@Param("parentId") UUID parentId, @Param("now") Instant now);

    /** Housekeeping: holds past their expiry are marked EXPIRED. They stopped reserving money at expires_at already. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Authorization a set a.status = com.bilal.neobank.card.auth.AuthorizationStatus.EXPIRED,
                   a.releasedAt = :now
            where a.status = com.bilal.neobank.card.auth.AuthorizationStatus.AUTHORIZED and a.expiresAt <= :now
            """)
    int markExpired(@Param("now") Instant now);
}
