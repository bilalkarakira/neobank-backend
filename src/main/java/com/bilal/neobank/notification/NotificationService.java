package com.bilal.neobank.notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Security notices in an in-app inbox, standing in for an email channel. Two ways to write one:
 * {@code notify...} joins the caller's transaction (the notice exists exactly when the change it describes does), while
 * the lock notice is written independently, because the failed attempts that cause a lock are themselves committed
 * even though their request rolls back.
 */
@Service
public class NotificationService {

    public static final int LIST_LIMIT = 50;

    public record Item(UUID id, String kind, String title, String body, java.time.Instant createdAt, boolean read) {
    }

    public record Inbox(long unreadCount, List<Item> items) {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm 'UTC'");

    private final JdbcClient jdbc;
    private final Clock clock;

    public NotificationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Call BEFORE the new login is stored. A notice is raised only when the customer has logged in before but never from
     * this device and address together: their very first login is not news, and a familiar place is not either.
     */
    @Transactional
    public void loginFrom(UUID userId, String device, String ip) {
        boolean hasHistory = jdbc.sql("select exists (select 1 from refresh_tokens where user_id = :user)")
                .param("user", userId).query(Boolean.class).single();
        if (!hasHistory) {
            return;
        }
        boolean seen = jdbc.sql("""
                select exists (select 1 from refresh_tokens where user_id = :user
                               and device is not distinct from :device and ip is not distinct from :ip)
                """).param("user", userId).param("device", device).param("ip", ip).query(Boolean.class).single();
        if (!seen) {
            insert(userId, "NEW_DEVICE_LOGIN", "New login to your account",
                    "Someone signed in from %s (address %s) on %s. If that was you, nothing more is needed. If not, change your password now: that signs out every other device."
                            .formatted(device, ip, WHEN.format(now())));
        }
    }

    /** Joins the caller's transaction: the notice exists exactly when the freeze does. */
    @Transactional
    public void cardFrozenByPin(UUID userId, String last4) {
        insert(userId, "CARD_FROZEN_PIN", "Your card was frozen",
                "Your card ending %s was frozen on %s after too many wrong PINs. If that was you, unfreeze it on the Cards page. If not, keep it frozen and cancel it."
                        .formatted(last4, WHEN.format(now())));
    }

    /** A cleared card payment that was more than the account could cover: the rest is owed to the bank. */
    @Transactional
    public void cardShortfall(UUID userId, String merchant, String paid, String owed) {
        insert(userId, "CARD_SHORTFALL", "You owe the bank money from a card payment",
                "A card payment at %s cleared on %s for more than you had available. %s was taken from your account and %s is owed to the bank. It is paid off by your next deposit or refund, and card payments are declined until then."
                        .formatted(merchant, WHEN.format(now()), paid, owed));
    }

    @Transactional
    public void passwordChanged(UUID userId) {
        insert(userId, "PASSWORD_CHANGED", "Your password was changed",
                "Your password was changed on %s and every other device was signed out. If this was not you, contact support."
                        .formatted(WHEN.format(now())));
    }

    /**
     * Too many wrong passwords locked this email. Nothing happens for an address with no customer (the lock itself is
     * identical either way, so it reveals nothing). Written in its own transaction: see the class comment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginLocked(String normalizedEmail, java.time.Duration lockDuration) {
        Optional<UUID> user = jdbc.sql("select id from users where email = :email").param("email", normalizedEmail)
                .query(UUID.class).optional();
        user.ifPresent(id -> insert(id, "LOGIN_LOCKED", "Your login was locked",
                "There were too many wrong passwords for your account on %s, so logging in is locked for %d minutes. If that was not you, someone is guessing your password: choose a strong one once the lock ends."
                        .formatted(WHEN.format(now()), lockDuration.toMinutes())));
    }

    @Transactional(readOnly = true)
    public Inbox inbox(UUID userId) {
        long unread = jdbc.sql("select count(*) from notifications where user_id = :user and read_at is null")
                .param("user", userId).query(Long.class).single();
        List<Item> items = jdbc.sql("""
                select id, kind, title, body, created_at, read_at from notifications where user_id = :user
                order by created_at desc, id desc limit :limit
                """).param("user", userId).param("limit", LIST_LIMIT)
                .query((rs, i) -> new Item(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("title"),
                        rs.getString("body"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("read_at") != null))
                .list();
        return new Inbox(unread, items);
    }

    /** Idempotent. False when the notice does not exist or belongs to someone else. */
    @Transactional
    public boolean markRead(UUID userId, UUID id) {
        boolean owned = jdbc.sql("select exists (select 1 from notifications where id = :id and user_id = :user)")
                .param("id", id).param("user", userId).query(Boolean.class).single();
        if (owned) {
            jdbc.sql("update notifications set read_at = :now where id = :id and user_id = :user and read_at is null")
                    .param("now", now()).param("id", id).param("user", userId).update();
        }
        return owned;
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return jdbc.sql("update notifications set read_at = :now where user_id = :user and read_at is null")
                .param("now", now()).param("user", userId).update();
    }

    /** Housekeeping: removes notices created more than {@code retention} ago (read or not). Returns how many. */
    @Transactional
    public int purge(java.time.Duration retention) {
        return jdbc.sql("delete from notifications where created_at < :cutoff")
                .param("cutoff", now().minus(retention)).update();
    }

    private void insert(UUID userId, String kind, String title, String body) {
        jdbc.sql("insert into notifications (id, user_id, kind, title, body, created_at) values (:id, :user, :kind, :title, :body, :now)")
                .param("id", UUID.randomUUID()).param("user", userId).param("kind", kind).param("title", title)
                .param("body", body).param("now", now()).update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
