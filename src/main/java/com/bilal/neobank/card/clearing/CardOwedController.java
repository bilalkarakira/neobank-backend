package com.bilal.neobank.card.clearing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the signed-in customer owes the bank because a cleared card payment was more than their account could cover. */
@RestController
public class CardOwedController {

    public record Item(UUID accountId, String amount, String outstanding, Instant createdAt) {
    }

    public record Owed(String total, List<Item> items) {
    }

    private final JdbcClient jdbc;

    public CardOwedController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/cards/owed")
    @Transactional(readOnly = true)
    public Owed owed(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<Item> items = jdbc.sql("""
                select account_id, amount, outstanding, created_at from card_receivables
                where user_id = :u and outstanding > 0 order by created_at desc, id desc
                """).param("u", userId).query((rs, i) -> new Item(rs.getObject(1, UUID.class), money(rs.getBigDecimal(2)),
                money(rs.getBigDecimal(3)), rs.getObject(4, java.time.OffsetDateTime.class).toInstant())).list();
        BigDecimal total = items.stream().map(i -> new BigDecimal(i.outstanding())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Owed(money(total), items);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
