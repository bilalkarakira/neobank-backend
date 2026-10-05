package com.bilal.neobank.history;

import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.ledger.TransactionType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Filters are optional, so the WHERE clause is assembled from fixed fragments; every value is a named parameter
 * and the ORDER BY comes from the {@link HistorySort} allow-list, so nothing from the request is ever concatenated.
 */
@Repository
public class TransactionHistoryRepository {

    private static final String FROM = """
            from ledger_entries e
            join transactions t on t.id = e.transaction_id
            join accounts cp on cp.id = case when e.amount < 0 then t.destination_account_id
                                             else t.source_account_id end
            left join users u on u.id = cp.user_id
            left join categories cat on cat.id = t.category_id
            where e.account_id = :accountId
            """;

    private final JdbcClient jdbc;

    public TransactionHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<HistoryRow> find(UUID accountId, HistoryFilter filter, int limit, long offset) {
        Map<String, Object> params = new HashMap<>();
        String where = where(accountId, filter, params);
        params.put("limit", limit);
        params.put("offset", offset);
        String sql = """
                select t.id as transaction_id, t.type, e.amount, e.balance_after, t.description, e.created_at,
                       cp.type as cp_type, cp.user_id as cp_user_id, cp.iban as cp_iban, cp.nickname as cp_nickname,
                       u.first_name as cp_first_name, u.last_name as cp_last_name, cat.name as category_name,
                       exists (select 1 from recurring_runs r where r.transaction_id = t.id) as recurring
                """ + FROM + where + " order by " + filter.sort().orderBy() + " limit :limit offset :offset";

        return jdbc.sql(sql).params(params).query((rs, i) -> new HistoryRow(
                rs.getObject("transaction_id", UUID.class),
                TransactionType.valueOf(rs.getString("type")),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_after"),
                rs.getString("description"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                AccountType.valueOf(rs.getString("cp_type")),
                rs.getObject("cp_user_id", UUID.class),
                rs.getString("cp_iban"),
                rs.getString("cp_nickname"),
                rs.getString("cp_first_name"),
                rs.getString("cp_last_name"),
                rs.getString("category_name"), rs.getBoolean("recurring"))).list();
    }

    public long count(UUID accountId, HistoryFilter filter) {
        Map<String, Object> params = new HashMap<>();
        String where = where(accountId, filter, params);
        return jdbc.sql("select count(*) " + FROM + where).params(params).query(Long.class).single();
    }

    private static String where(UUID accountId, HistoryFilter f, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder();
        params.put("accountId", accountId);

        if (f.from() != null) {
            sql.append(" and e.created_at >= :from");
            params.put("from", f.from().atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (f.to() != null) {
            sql.append(" and e.created_at < :toExclusive"); // 'to' is inclusive: up to the start of the next day
            params.put("toExclusive", f.to().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (f.type() != null) {
            sql.append(" and t.type = :type");
            params.put("type", f.type().name());
        }
        if (f.direction() == Direction.IN) {
            sql.append(" and e.amount > 0");
        } else if (f.direction() == Direction.OUT) {
            sql.append(" and e.amount < 0");
        }
        if (f.minAmount() != null) {
            sql.append(" and abs(e.amount) >= :minAmount");
            params.put("minAmount", f.minAmount());
        }
        if (f.maxAmount() != null) {
            sql.append(" and abs(e.amount) <= :maxAmount");
            params.put("maxAmount", f.maxAmount());
        }
        if (f.q() != null) {
            sql.append(" and t.description ilike :q escape '\\'");
            params.put("q", "%" + escapeLike(f.q()) + "%");
        }
        return sql.toString();
    }

    /** The user's text is a literal: '%' and '_' must not act as wildcards. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
