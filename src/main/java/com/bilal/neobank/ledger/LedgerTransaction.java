package com.bilal.neobank.ledger;

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

/** One business event (a deposit, a transfer): amount moved from the source to the destination account. */
@Entity
@Table(name = "transactions")
public class LedgerTransaction {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(name = "initiated_by", nullable = false)
    private UUID initiatedBy;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(length = 100)
    private String description;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "destination_account_id", nullable = false)
    private UUID destinationAccountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Null = uncategorized. Only set on transfers to other customers (it is the sender's spending category). */
    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
        // required by JPA
    }

    public LedgerTransaction(TransactionType type, UUID initiatedBy, String idempotencyKey, String requestHash,
            String description, UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount, UUID categoryId) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.initiatedBy = initiatedBy;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.description = description;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.categoryId = categoryId;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres keeps microseconds
    }

    public UUID getId() { return id; }
    public TransactionType getType() { return type; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getDescription() { return description; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public UUID getCategoryId() { return categoryId; }
    public Instant getCreatedAt() { return createdAt; }
}
