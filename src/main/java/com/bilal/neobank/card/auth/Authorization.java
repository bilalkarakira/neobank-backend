package com.bilal.neobank.card.auth;

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
 * One request the issuer decided. Only AUTHORIZED rows (not yet expired) hold money. The PAN is not stored, only its
 * keyed hash and the last four digits.
 */
@Entity
@Table(name = "authorizations")
public class Authorization {

    /** The fields every request has; the card and account are null when the card is unknown. */
    public record Request(String mti, String stan, String rrn, String terminalId, String merchantId,
            String merchantName, String mcc, String acquirerId, String processingCode, String entryMode,
            String localDate, String transmittedAt, BigDecimal amount, String currency) {
    }

    @Id
    private UUID id;

    @Column(name = "card_id")
    private UUID cardId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "pan_hash", nullable = false, length = 64)
    private String panHash;

    @Column(nullable = false, length = 4)
    private String last4;

    @Column(nullable = false, length = 4)
    private String mti;

    @Column(nullable = false, length = 6)
    private String stan;

    @Column(nullable = false, length = 12)
    private String rrn;

    @Column(name = "terminal_id", nullable = false, length = 8)
    private String terminalId;

    @Column(name = "merchant_id", nullable = false, length = 15)
    private String merchantId;

    @Column(name = "merchant_name", length = 40)
    private String merchantName;

    @Column(length = 4)
    private String mcc;

    @Column(name = "acquirer_id", nullable = false, length = 11)
    private String acquirerId;

    @Column(name = "processing_code", length = 6)
    private String processingCode;

    @Column(name = "entry_mode", length = 3)
    private String entryMode;

    @Column(name = "local_date", nullable = false, length = 4)
    private String localDate;

    @Column(name = "transmitted_at", nullable = false, length = 10)
    private String transmittedAt;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "response_code", nullable = false, length = 2)
    private String responseCode;

    @Column(name = "auth_code", length = 6)
    private String authCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthorizationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected Authorization() {
        // required by JPA
    }

    private Authorization(UUID cardId, UUID accountId, String panHash, String last4, Request r, String responseCode,
            String authCode, AuthorizationStatus status, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.cardId = cardId;
        this.accountId = accountId;
        this.panHash = panHash;
        this.last4 = last4;
        this.mti = r.mti();
        this.stan = r.stan();
        this.rrn = r.rrn();
        this.terminalId = r.terminalId();
        this.merchantId = r.merchantId();
        this.merchantName = r.merchantName();
        this.mcc = r.mcc();
        this.acquirerId = r.acquirerId();
        this.processingCode = r.processingCode();
        this.entryMode = r.entryMode();
        this.localDate = r.localDate();
        this.transmittedAt = r.transmittedAt();
        this.amount = r.amount();
        this.currency = r.currency();
        this.responseCode = responseCode;
        this.authCode = authCode;
        this.status = status;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** An approval: holds the amount until {@code expiresAt}. */
    public static Authorization approved(UUID cardId, UUID accountId, String panHash, String last4, Request request,
            String authCode, Instant now, Instant expiresAt) {
        return new Authorization(cardId, accountId, panHash, last4, request, ResponseCode.APPROVED, authCode,
                AuthorizationStatus.AUTHORIZED, now, expiresAt);
    }

    public static Authorization declined(UUID cardId, UUID accountId, String panHash, String last4, Request request,
            String responseCode, Instant now) {
        return new Authorization(cardId, accountId, panHash, last4, request, responseCode, null,
                AuthorizationStatus.DECLINED, now, null);
    }

    /** What is left of a reversal that arrived before its original (see the P2 spec): the original will be refused. */
    public static Authorization tombstone(UUID cardId, UUID accountId, String panHash, String last4, Request request,
            Instant now) {
        Authorization a = new Authorization(cardId, accountId, panHash, last4, request, ResponseCode.DO_NOT_HONOUR,
                null, AuthorizationStatus.REVERSED, now, null);
        a.releasedAt = a.createdAt;
        return a;
    }

    public void reverse(Instant now) {
        this.status = AuthorizationStatus.REVERSED;
        this.releasedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** True while the amount is still reserved. */
    public boolean holdsMoney(Instant now) {
        return status == AuthorizationStatus.AUTHORIZED && expiresAt != null && expiresAt.isAfter(now);
    }

    public UUID getId() { return id; }
    public UUID getCardId() { return cardId; }
    public UUID getAccountId() { return accountId; }
    public String getPanHash() { return panHash; }
    public String getLast4() { return last4; }
    public String getMti() { return mti; }
    public String getStan() { return stan; }
    public String getRrn() { return rrn; }
    public String getTerminalId() { return terminalId; }
    public String getMerchantId() { return merchantId; }
    public String getMerchantName() { return merchantName; }
    public String getMcc() { return mcc; }
    public String getAcquirerId() { return acquirerId; }
    public String getProcessingCode() { return processingCode; }
    public String getEntryMode() { return entryMode; }
    public String getLocalDate() { return localDate; }
    public String getTransmittedAt() { return transmittedAt; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getResponseCode() { return responseCode; }
    public String getAuthCode() { return authCode; }
    public AuthorizationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getReleasedAt() { return releasedAt; }
}
