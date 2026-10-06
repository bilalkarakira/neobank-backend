package com.bilal.neobank.card.clearing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What customers owe the bank for the part of a cleared card payment their account could not cover. The matching ledger
 * move (SYSTEM-CARD-RECEIVABLE) is made by the callers; this table says WHO owes and how much is still open.
 */
@Component
public class CardReceivables {

    private final JdbcClient jdbc;

    public CardReceivables(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasOutstanding(UUID accountId) {
        return jdbc.sql("select exists (select 1 from card_receivables where account_id = :a and outstanding > 0)")
                .param("a", accountId).query(Boolean.class).single();
    }

    public BigDecimal outstanding(UUID accountId) {
        return jdbc.sql("select coalesce(sum(outstanding), 0) from card_receivables where account_id = :a")
                .param("a", accountId).query(BigDecimal.class).single();
    }

    @Transactional
    public void add(UUID userId, UUID accountId, UUID clearingItemId, BigDecimal amount, Instant now) {
        jdbc.sql("""
                insert into card_receivables (id, user_id, account_id, clearing_item_id, amount, outstanding, created_at)
                values (:id, :u, :a, :i, :amount, :amount, :now)
                """).param("id", UUID.randomUUID()).param("u", userId).param("a", accountId).param("i", clearingItemId)
                .param("amount", amount).param("now", Timestamp.from(now)).update();
    }

    /** Pays off {@code amount} of what the account owes, oldest first. The caller made the ledger move. */
    @Transactional
    public void reduce(UUID accountId, BigDecimal amount, Instant now) {
        record Open(UUID id, BigDecimal outstanding) {
        }
        List<Open> open = jdbc.sql("""
                select id, outstanding from card_receivables where account_id = :a and outstanding > 0
                order by created_at, id for update
                """).param("a", accountId).query((rs, i) -> new Open(rs.getObject(1, UUID.class), rs.getBigDecimal(2))).list();
        BigDecimal left = amount;
        for (Open row : open) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal pay = left.min(row.outstanding());
            BigDecimal remaining = row.outstanding().subtract(pay);
            jdbc.sql("update card_receivables set outstanding = :o, settled_at = cast(:s as timestamptz) where id = :id")
                    .param("o", remaining).param("s", remaining.signum() == 0 ? Timestamp.from(now) : null)
                    .param("id", row.id()).update();
            left = left.subtract(pay);
        }
        if (left.signum() != 0) {
            throw new IllegalStateException("more paid off than was owed");
        }
    }
}
