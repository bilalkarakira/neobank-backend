package com.bilal.neobank.account;

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

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    /** Null only for SYSTEM accounts. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, unique = true, length = 34)
    private String iban;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(length = 40)
    private String nickname;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
        // required by JPA
    }

    public Account(UUID userId, String iban, AccountType type, String nickname) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.iban = iban;
        this.type = type;
        this.currency = "USD";
        this.status = AccountStatus.ACTIVE;
        this.nickname = nickname;
        this.balance = BigDecimal.ZERO;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres keeps microseconds; keep API values identical to stored ones
    }

    /** Only the ledger moves money; it calls this while holding a row lock on the account. */
    public void applyDelta(BigDecimal delta) {
        this.balance = this.balance.add(delta);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getIban() { return iban; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public AccountStatus getStatus() { return status; }
    public String getNickname() { return nickname; }
    public BigDecimal getBalance() { return balance; }
    public Instant getCreatedAt() { return createdAt; }
}
