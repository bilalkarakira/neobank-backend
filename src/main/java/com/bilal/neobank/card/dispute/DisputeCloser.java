package com.bilal.neobank.card.dispute;

import com.bilal.neobank.notification.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Closes the disputes the shop did not answer: an OPEN dispute (the network took the chargeback) whose answer window has passed is
 * WON, and the customer's provisional credit is final. One statement decides which rows move, so two runs at once close each
 * dispute once; nothing is posted (the money moved when the credit was given).
 */
@Service
public class DisputeCloser {

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final DisputeProperties properties;
    private final Clock clock;

    public DisputeCloser(JdbcClient jdbc, NotificationService notifications, DisputeProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return how many disputes were closed as won */
    @Transactional
    public int closeDue() {
        Instant now = clock.instant();
        record Closed(UUID userId, String merchant, BigDecimal amount) {
        }
        List<Closed> closed = jdbc.sql("""
                update card_disputes set status = 'WON', closed_at = :now, outcome = 'The shop did not answer in time: your credit is final.'
                where status = 'OPEN' and opened_at <= :cutoff returning user_id, merchant_name, amount
                """).param("now", Timestamp.from(now)).param("cutoff", Timestamp.from(now.minus(properties.window())))
                .query((rs, n) -> new Closed(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3))).list();
        for (Closed c : closed) {
            notifications.disputeWon(c.userId(), c.merchant(), DisputeService.money(c.amount()));
        }
        return closed.size();
    }
}
