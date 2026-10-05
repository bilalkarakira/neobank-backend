package com.bilal.neobank.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RefreshTokenRepository {

    public record Row(UUID id, UUID userId, UUID familyId, OffsetDateTime expiresAt, OffsetDateTime familyExpiresAt,
            OffsetDateTime rotatedAt, OffsetDateTime revokedAt, String device, String ip, OffsetDateTime familyStartedAt) {
    }

    /** One login that can still be refreshed. startedAt/lastActiveAt come from the tokens of its family. */
    public record Session(UUID familyId, String device, String ip, OffsetDateTime startedAt,
            OffsetDateTime lastActiveAt, OffsetDateTime expiresAt) {
    }

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID userId, UUID familyId, String tokenHash, OffsetDateTime createdAt,
            OffsetDateTime expiresAt, OffsetDateTime familyExpiresAt, String device, String ip,
            OffsetDateTime familyStartedAt) {
        jdbc.sql("""
                insert into refresh_tokens (id, user_id, family_id, token_hash, created_at, expires_at,
                                            family_expires_at, device, ip, family_started_at)
                values (:id, :user, :family, :hash, :created, :expires, :familyExpires, :device, :ip, :started)
                """)
                .param("id", id).param("user", userId).param("family", familyId).param("hash", tokenHash)
                .param("created", createdAt).param("expires", expiresAt).param("familyExpires", familyExpiresAt)
                .param("device", device).param("ip", ip).param("started", familyStartedAt)
                .update();
    }

    /** Locked, so two requests presenting the same token are handled one after the other. */
    public Optional<Row> findByHashForUpdate(String tokenHash) {
        return jdbc.sql("select * from refresh_tokens where token_hash = :hash for update").param("hash", tokenHash)
                .query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("family_expires_at", OffsetDateTime.class),
                        rs.getObject("rotated_at", OffsetDateTime.class), rs.getObject("revoked_at", OffsetDateTime.class),
                        rs.getString("device"), rs.getString("ip"), rs.getObject("family_started_at", OffsetDateTime.class)))
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

    /** Owner-scoped: a family id that belongs to someone else matches nothing. @return rows revoked */
    public int revokeFamilyOfUser(UUID userId, UUID familyId, OffsetDateTime at) {
        return jdbc.sql("""
                update refresh_tokens set revoked_at = :at
                where user_id = :user and family_id = :family and revoked_at is null
                """).param("at", at).param("user", userId).param("family", familyId).update();
    }

    /** Every login of the user except one (null keeps none). @return rows revoked */
    public int revokeAllExcept(UUID userId, UUID keepFamilyId, OffsetDateTime at) {
        return jdbc.sql("""
                update refresh_tokens set revoked_at = :at
                where user_id = :user and revoked_at is null and (cast(:keep as uuid) is null or family_id <> :keep)
                """).param("at", at).param("user", userId).param("keep", keepFamilyId).update();
    }

    /**
     * The user's logins that can still be refreshed: a family that has a live (not rotated, not revoked, not expired)
     * token. Newest activity first.
     */
    public List<Session> activeSessions(UUID userId, OffsetDateTime now) {
        return jdbc.sql("""
                select family_id, max(device) device, max(ip) ip, min(family_started_at) started_at,
                       max(created_at) last_active_at,
                       max(expires_at) filter (where rotated_at is null and revoked_at is null) expires_at
                from refresh_tokens where user_id = :user
                group by family_id
                having bool_or(rotated_at is null and revoked_at is null and expires_at > :now and family_expires_at > :now)
                order by max(created_at) desc, family_id
                """).param("user", userId).param("now", now)
                .query((rs, i) -> new Session(rs.getObject("family_id", UUID.class), rs.getString("device"),
                        rs.getString("ip"), rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("last_active_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .list();
    }

    /** Tokens that expired before the cutoff can no longer be replayed, so keeping them has no value. */
    public int deleteExpiredBefore(OffsetDateTime cutoff) {
        return jdbc.sql("delete from refresh_tokens where expires_at < :cutoff").param("cutoff", cutoff).update();
    }
}
