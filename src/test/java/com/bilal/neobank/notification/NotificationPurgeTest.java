package com.bilal.neobank.notification;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Old notices are removed after the retention period, whether read or not, and only those. */
@SpringBootTest(properties = "app.notifications.scheduler-enabled=true")
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class NotificationPurgeTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Autowired
    DatabaseCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    NotificationService service;

    @Autowired
    NotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        clock.set(NOW);
        jdbc.update("""
                insert into users (id, email, password_hash, first_name, last_name, role, created_at)
                values ('11111111-1111-1111-1111-111111111111', 'alice@example.com', 'x', 'A', 'B', 'CUSTOMER', now())
                """);
    }

    private void notice(String title, Duration age, boolean read) {
        Instant created = NOW.minus(age);
        jdbc.update("""
                insert into notifications (id, user_id, kind, title, body, created_at, read_at)
                values (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'LOGIN_LOCKED', ?, 'b', ?::timestamptz, ?::timestamptz)
                """, title, created.toString(), read ? created.toString() : null);
    }

    private List<String> titles() {
        return jdbc.queryForList("select title from notifications order by title", String.class);
    }

    @Test
    void onlyNoticesOlderThanTheRetentionAreRemovedReadOrUnread() throws Exception {
        notice("fresh", Duration.ofDays(1), false);
        notice("almost", Duration.ofDays(90).minusSeconds(1), false);
        notice("exactly", Duration.ofDays(90), false); // not OLDER than 90 days: kept
        notice("just-over-unread", Duration.ofDays(90).plusSeconds(1), false);
        notice("old-read", Duration.ofDays(200), true);

        assertThat(service.purge(Duration.ofDays(90))).isEqualTo(2);

        assertThat(titles()).containsExactly("almost", "exactly", "fresh");
    }

    @Test
    void theScheduledJobUsesTheConfiguredNinetyDaysAndSurvivesAnEmptyTable() {
        scheduler.purge(); // nothing to do
        notice("fresh", Duration.ofDays(5), false);
        notice("old", Duration.ofDays(120), false);

        scheduler.purge();

        assertThat(titles()).containsExactly("fresh");
    }
}
