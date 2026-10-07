package com.bilal.neobank.admin;

import com.bilal.neobank.card.dispute.DisputeCloser;
import com.bilal.neobank.card.dispute.DisputeProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Admin view of card disputes: every dispute with its state, and a button that closes the ones whose window has passed. ADMIN only. Shows the last four digits only. */
@RestController
public class AdminDisputeController {

    public record Row(UUID id, String email, String merchantName, String last4, String amount, String reason, String note, String status, String outcome,
            boolean lateRepresentment, Instant filedAt, Instant openedAt, Instant closedAt) {
    }

    public record Overview(long windowSeconds, int filed, int open, int won, int lost, List<Row> disputes) {
    }

    public record CloseResult(int closed) {
    }

    private final JdbcClient jdbc;
    private final DisputeCloser closer;
    private final DisputeProperties properties;
    private final AdminRepository adminRepository;
    private final Clock clock;

    public AdminDisputeController(JdbcClient jdbc, DisputeCloser closer, DisputeProperties properties, AdminRepository adminRepository, Clock clock) {
        this.jdbc = jdbc;
        this.closer = closer;
        this.properties = properties;
        this.adminRepository = adminRepository;
        this.clock = clock;
    }

    @GetMapping("/api/admin/disputes")
    @Transactional(readOnly = true)
    public Overview overview() {
        List<Row> rows = jdbc.sql("""
                select d.id, u.email, d.merchant_name, d.last4, d.amount, d.reason, d.note, d.status, d.outcome, d.late_representment, d.filed_at, d.opened_at, d.closed_at
                from card_disputes d join users u on u.id = d.user_id order by d.filed_at desc, d.id desc limit 100
                """).query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBigDecimal(5).setScale(2, RoundingMode.UNNECESSARY).toPlainString(), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getBoolean(10), instant(rs.getObject(11, OffsetDateTime.class)), instant(rs.getObject(12, OffsetDateTime.class)),
                instant(rs.getObject(13, OffsetDateTime.class)))).list();
        int[] counts = new int[4];
        jdbc.sql("select status, count(*) from card_disputes group by status").query((rs, i) -> {
            switch (rs.getString(1)) {
                case "FILED" -> counts[0] = rs.getInt(2);
                case "OPEN" -> counts[1] = rs.getInt(2);
                case "WON" -> counts[2] = rs.getInt(2);
                default -> counts[3] = rs.getInt(2);
            }
            return 0;
        }).list();
        return new Overview(properties.window().toSeconds(), counts[0], counts[1], counts[2], counts[3], rows);
    }

    /** Closes the disputes whose answer window has passed, now (the background job does the same every few seconds). Recorded in the audit log. */
    @PostMapping("/api/admin/disputes/close-due")
    public CloseResult closeDue(@AuthenticationPrincipal Jwt jwt) {
        int closed = closer.closeDue();
        adminRepository.insertAction(UUID.fromString(jwt.getSubject()), "CLOSE_DISPUTES", null, null, "closed=%d".formatted(closed),
                clock.instant().atOffset(ZoneOffset.UTC));
        return new CloseResult(closed);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
