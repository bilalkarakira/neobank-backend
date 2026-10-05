package com.bilal.neobank.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {
        // required by JPA
    }

    public User(String email, String passwordHash, String firstName, String lastName, Role role) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.role = role;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres keeps microseconds; keep API values identical to stored ones
    }

    /** Takes an already-hashed password; hashing is the caller's job so this entity never sees plain text. */
    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
