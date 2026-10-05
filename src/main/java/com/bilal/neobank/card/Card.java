package com.bilal.neobank.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A debit card. The clear PAN is never stored: only its keyed hash and the last four digits. */
@Entity
@Table(name = "cards")
public class Card {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "pan_hash", nullable = false, length = 64)
    private String panHash;

    @Column(nullable = false, length = 4)
    private String last4;

    @Column(nullable = false, length = 6)
    private String bin;

    /** YYMM, valid through the end of that month (UTC). */
    @Column(nullable = false, length = 4)
    private String expiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "freeze_reason", length = 20)
    private FreezeReason freezeReason;

    @Column(name = "pin_hash", nullable = false, length = 64)
    private String pinHash;

    @Column(name = "pin_failures", nullable = false)
    private int pinFailures;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(length = 40)
    private String nickname;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Card() {
        // required by JPA
    }

    public Card(UUID id, UUID userId, UUID accountId, String panHash, String last4, String bin, String expiry,
            String pinHash, BigDecimal dailyLimit, String nickname, Instant now) {
        this.id = id;
        this.userId = userId;
        this.accountId = accountId;
        this.panHash = panHash;
        this.last4 = last4;
        this.bin = bin;
        this.expiry = expiry;
        this.status = CardStatus.ACTIVE;
        this.pinHash = pinHash;
        this.dailyLimit = dailyLimit;
        this.nickname = nickname;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void freeze(FreezeReason reason) {
        this.status = CardStatus.FROZEN;
        this.freezeReason = reason;
    }

    public void unfreeze() {
        this.status = CardStatus.ACTIVE;
        this.freezeReason = null;
        this.pinFailures = 0;
    }

    public void cancel(Instant now) {
        this.status = CardStatus.CANCELLED;
        this.freezeReason = null;
        this.cancelledAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void changeDailyLimit(BigDecimal limit) {
        this.dailyLimit = limit;
    }

    public void setPinFailures(int failures) {
        this.pinFailures = failures;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getAccountId() { return accountId; }
    public String getPanHash() { return panHash; }
    public String getLast4() { return last4; }
    public String getBin() { return bin; }
    public String getExpiry() { return expiry; }
    public CardStatus getStatus() { return status; }
    public FreezeReason getFreezeReason() { return freezeReason; }
    public String getPinHash() { return pinHash; }
    public int getPinFailures() { return pinFailures; }
    public BigDecimal getDailyLimit() { return dailyLimit; }
    public String getNickname() { return nickname; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
