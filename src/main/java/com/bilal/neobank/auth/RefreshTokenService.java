package com.bilal.neobank.auth;

import com.bilal.neobank.ledger.Fingerprint;
import com.bilal.neobank.user.User;
import com.bilal.neobank.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import com.bilal.neobank.notification.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens. The raw token only ever exists in the cookie; the database keeps its
 * SHA-256 hash. The raw value is 256 random bits, so a fast hash is enough (there is nothing to brute-force).
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** @param rawToken the value for the cookie; never stored */
    public record Issued(String rawToken, Instant expiresAt, UUID familyId) {
    }

    public record Rotation(User user, Issued issued) {
    }

    private final RefreshTokenRepository repository;
    private final UserRepository userRepository;
    private final RefreshProperties properties;
    private final Clock clock;
    private final NotificationService notifications;

    public RefreshTokenService(RefreshTokenRepository repository, UserRepository userRepository,
            RefreshProperties properties, Clock clock, NotificationService notifications) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.properties = properties;
        this.clock = clock;
        this.notifications = notifications;
    }

    /** Starts a new family: one per login. [device] and [ip] describe where it came from, for the session list. */
    @Transactional
    public Issued issueForLogin(User user, String device, String ip) {
        OffsetDateTime now = now();
        OffsetDateTime familyExpires = now.plus(properties.maxLifetime());
        notifications.loginFrom(user.getId(), device, ip); // before the new login is stored: it compares with the earlier ones
        return insert(user.getId(), UUID.randomUUID(), now, familyExpires, device, ip, now);
    }

    /**
     * Exchanges a valid token for a fresh one in the same family. An invalid token is always the same answer, so the
     * caller cannot tell "unknown" from "expired" from "revoked".
     *
     * noRollbackFor: revoking the family on reuse must be committed even though we then throw.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        OffsetDateTime now = now();
        RefreshTokenRepository.Row row = repository.findByHashForUpdate(hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (row.revokedAt() != null || !row.expiresAt().isAfter(now) || !row.familyExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }
        if (row.rotatedAt() != null) {
            // Already exchanged. Within the grace window this is most likely a second tab or a retried request that
            // raced the first one: refuse it, but leave the login alone. Later than that, someone replayed an old
            // token, so whoever holds the newer one is no longer trustworthy either: end the whole login.
            if (Duration.between(row.rotatedAt(), now).compareTo(properties.reuseGrace()) > 0) {
                repository.revokeFamily(row.familyId(), now);
                log.warn("Refresh token reuse detected for user {}: login family {} revoked", row.userId(), row.familyId());
            }
            throw new InvalidRefreshTokenException();
        }

        // a deleted user's tokens are worthless, and the role is read fresh so changes apply at every refresh
        User user = userRepository.findById(row.userId()).orElseThrow(InvalidRefreshTokenException::new);
        repository.markRotated(row.id(), now);
        return new Rotation(user, insert(row.userId(), row.familyId(), now, row.familyExpiresAt(), row.device(), row.ip(),
                row.familyStartedAt()));
    }

    /** Logout of one device: ends the login this token belongs to. Unknown tokens are ignored. */
    @Transactional
    public void revokeLoginOf(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByHashForUpdate(hash(rawToken)).ifPresent(row -> repository.revokeFamily(row.familyId(), now()));
    }

    /** The user's logins that can still be refreshed, most recently active first. */
    @Transactional(readOnly = true)
    public List<RefreshTokenRepository.Session> activeSessions(UUID userId) {
        return repository.activeSessions(userId, now());
    }

    /** Ends one login of this user. @return false when the id is not one of THEIR logins */
    @Transactional
    public boolean revokeSession(UUID userId, UUID sessionId) {
        return repository.revokeFamilyOfUser(userId, sessionId, now()) > 0;
    }

    /** Ends every login except the given one (null ends all). */
    @Transactional
    public int revokeAllExcept(UUID userId, UUID keepSessionId) {
        return repository.revokeAllExcept(userId, keepSessionId, now());
    }

    /** Logout everywhere. @return how many tokens were revoked */
    @Transactional
    public int revokeAllFor(UUID userId) {
        return repository.revokeAllForUser(userId, now());
    }

    /** Housekeeping: removes tokens that expired more than a day ago. */
    @Transactional
    public int purgeExpired() {
        return repository.deleteExpiredBefore(now().minus(Duration.ofDays(1)));
    }

    private Issued insert(UUID userId, UUID familyId, OffsetDateTime now, OffsetDateTime familyExpires, String device,
            String ip, OffsetDateTime familyStartedAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime expires = now.plus(properties.ttl());
        if (expires.isAfter(familyExpires)) {
            expires = familyExpires; // sliding, but never past the absolute limit
        }
        repository.insert(UUID.randomUUID(), userId, familyId, hash(raw), now, expires, familyExpires, device, ip,
                familyStartedAt);
        return new Issued(raw, expires.toInstant(), familyId);
    }

    private static String hash(String raw) {
        return Fingerprint.sha256(raw);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
