package com.bilal.neobank.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RefreshTokenRepository {

    public record Row(UUID id, UUID userId, UUID familyId, OffsetDateTime expiresAt, OffsetDateTime familyExpiresAt,
            OffsetDateTime rotatedAt, OffsetDateTime revokedAt) {
    }

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID userId, UUID familyId, String tokenHash, OffsetDateTime createdAt,
            OffsetDateTime expiresAt, OffsetDateTime familyExpiresAt) {
        jdbc.sql("""
                insert into refresh_tokens (id, user_id, family_id, token_hash, created_at, expires_at, family_expires_at)
                values (:id, :user, :family, :hash, :created, :expires, :familyExpires)
                """)
                .param("id", id).param("user", userId).param("family", familyId).param("hash", tokenHash)
                .param("created", createdAt).param("expires", expiresAt).param("familyExpires", familyExpiresAt)
                .update();
    }

    /** Locked, so two requests presenting the same token are handled one after the other. */
    public Optional<Row> findByHashForUpdate(String tokenHash) {
        return jdbc.sql("select * from refresh_tokens where token_hash = :hash for update").param("hash", tokenHash)
                .query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("family_expires_at", OffsetDateTime.class),
                        rs.getObject("rotated_at", OffsetDateTime.class), rs.getObject("revoked_at", OffsetDateTime.class)))
                .optional();
    }

    public void markRotated(UUID id, OffsetDateTime at) {
        jdbc.sql("update refresh_tokens set rotated_at = :at where id = :id").param("at", at).param("id", id).update();
    }

    public void revokeFamily(UUID familyId, OffsetDateTime at) {
        jdbc.sql("update refresh_tokens set revoked_at = :at where family_id = :family and revoked_at is null")
                .param("at", at).param("family", familyId).update();
    }

    public int revokeAllForUser(UUID userId, OffsetDateTime at) {
        return jdbc.sql("update refresh_tokens set revoked_at = :at where user_id = :user and revoked_at is null")
                .param("at", at).param("user", userId).update();
    }

    /** Tokens that expired before the cutoff can no longer be replayed, so keeping them has no value. */
    public int deleteExpiredBefore(OffsetDateTime cutoff) {
        return jdbc.sql("delete from refresh_tokens where expires_at < :cutoff").param("cutoff", cutoff).update();
    }
}
