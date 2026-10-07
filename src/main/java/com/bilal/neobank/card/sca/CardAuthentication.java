package com.bilal.neobank.card.sca;

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

/**
 * The customer's approval of one online payment: the card, the amount and the shop it is for, and whether the customer said yes.
 * A simulation of what 3-D Secure asks of a cardholder (no real 3-D Secure messages). Used at most once.
 */
@Entity
@Table(name = "card_authentications")
public class CardAuthentication {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    private UUID id;

    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @Column(nullable = false, length = 8, unique = true)
    private String reference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "merchant_id", nullable = false, length = 15)
    private String merchantId;

    @Column(name = "merchant_name", nullable = false, length = 40)
    private String merchantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected CardAuthentication() {
        // required by JPA
    }

    public CardAuthentication(UUID cardId, String reference, BigDecimal amount, String merchantId, String merchantName, Instant now,
            Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.cardId = cardId;
        this.reference = reference;
        this.amount = amount;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.status = Status.PENDING;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public UUID getCardId() { return cardId; }
    public String getReference() { return reference; }
    public BigDecimal getAmount() { return amount; }
    public String getMerchantId() { return merchantId; }
    public String getMerchantName() { return merchantName; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getUsedAt() { return usedAt; }
}
