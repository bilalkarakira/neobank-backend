package com.bilal.neobank.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** One business event (a deposit, a transfer). Its ledger entries always sum to zero. */
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
        // required by JPA
    }

    public LedgerTransaction(TransactionType type, UUID initiatedBy, String idempotencyKey, String requestHash,
            String description) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.initiatedBy = initiatedBy;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.description = description;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres keeps microseconds; keep API values identical to stored ones
    }

    public UUID getId() { return id; }
    public TransactionType getType() { return type; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}
