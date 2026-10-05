package com.bilal.neobank.admin;

import com.bilal.neobank.card.auth.AuthorizationStatus;
import com.bilal.neobank.card.auth.ResponseCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only view of every card authorization decision, newest first (ADMIN only: SecurityConfig guards /api/admin/**).
 * Shows only what an operator needs: the last four digits, never anything that could rebuild a card number.
 */
@RestController
public class AdminAuthorizationController {

    public static final int MAX_PAGE_SIZE = 50;

    public record Item(UUID id, Instant createdAt, String last4, boolean cardKnown, String customerEmail,
            String merchantName, String amount, AuthorizationStatus status, String responseCode, String reason,
            String mti, boolean standIn, Instant expiresAt) {
    }

    public record Page(List<Item> items, long total, int page, int size) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public AdminAuthorizationController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/admin/authorizations")
    @Transactional(readOnly = true)
    public Page list(@RequestParam(required = false) AuthorizationStatus status,
            @RequestParam(required = false) String last4, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (last4 != null && !last4.matches("\\d{4}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "last4 must be exactly four digits");
        }
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Instant now = clock.instant();
        String where = " where (cast(:status as varchar) is null or a.status = :status)"
                + " and (cast(:last4 as varchar) is null or a.last4 = :last4)";
        long total = jdbc.sql("select count(*) from authorizations a" + where)
                .param("status", status == null ? null : status.name()).param("last4", last4)
                .query(Long.class).single();
        List<Item> items = jdbc.sql("""
                select a.id, a.created_at, a.last4, a.card_id is not null as card_known, u.email, a.merchant_name,
                       a.amount, a.status, a.response_code, a.mti, a.via_stand_in, a.expires_at
                from authorizations a
                left join cards c on c.id = a.card_id
                left join users u on u.id = c.user_id
                """ + where + " order by a.created_at desc, a.id desc limit :limit offset :offset")
                .param("status", status == null ? null : status.name()).param("last4", last4)
                .param("limit", safeSize).param("offset", safePage * safeSize)
                .query((rs, i) -> {
                    AuthorizationStatus raw = AuthorizationStatus.valueOf(rs.getString("status"));
                    OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
                    // a hold past its expiry is shown as EXPIRED even before the nightly job flips the row
                    AuthorizationStatus shown = raw == AuthorizationStatus.AUTHORIZED && expires != null
                            && !expires.toInstant().isAfter(now) ? AuthorizationStatus.EXPIRED : raw;
                    String code = rs.getString("response_code");
                    return new Item(rs.getObject("id", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("last4"),
                            rs.getBoolean("card_known"), rs.getString("email"), rs.getString("merchant_name"),
                            rs.getBigDecimal("amount").setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString(),
                            shown, code, ResponseCode.reason(code), rs.getString("mti"), rs.getBoolean("via_stand_in"),
                            shown == AuthorizationStatus.AUTHORIZED && expires != null ? expires.toInstant() : null);
                }).list();
        return new Page(items, total, safePage, safeSize);
    }
}
