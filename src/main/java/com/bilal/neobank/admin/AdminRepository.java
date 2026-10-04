package com.bilal.neobank.admin;

import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.admin.AdminDtos.AccountRow;
import com.bilal.neobank.admin.AdminDtos.AuditEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only statistics, account search and the audit log. Bank-internal (SYSTEM) accounts are never listed. */
@Repository
public class AdminRepository {

    public record VolumeRow(long count, BigDecimal amount) {
    }

    private final JdbcClient jdbc;

    public AdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long customerCount() {
        return jdbc.sql("select count(*) from users where role = 'CUSTOMER'").query(Long.class).single();
    }

    public Map<AccountStatus, Long> accountCountsByStatus() {
        Map<AccountStatus, Long> counts = new LinkedHashMap<>();
        for (AccountStatus status : AccountStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.sql("select status, count(*) n from accounts where type <> 'SYSTEM' group by status")
                .query((rs, i) -> counts.put(AccountStatus.valueOf(rs.getString("status")), rs.getLong("n"))).list();
        return counts;
    }

    /** Money customers hold: the sum of every non-system account balance. */
    public BigDecimal depositsHeld() {
        return jdbc.sql("select coalesce(sum(balance), 0) from accounts where type <> 'SYSTEM'")
                .query(BigDecimal.class).single();
    }

    public VolumeRow transfersSince(OffsetDateTime since) {
        return jdbc.sql("select count(*) n, coalesce(sum(amount), 0) total from transactions "
                + "where type = 'TRANSFER' and created_at >= :since").param("since", since)
                .query((rs, i) -> new VolumeRow(rs.getLong("n"), rs.getBigDecimal("total"))).single();
    }

    public BigDecimal interestPaid() {
        return jdbc.sql("select coalesce(sum(amount), 0) from transactions where type = 'INTEREST'")
                .query(BigDecimal.class).single();
    }

    public long recurringCount(String status) {
        return jdbc.sql("select count(*) from recurring_payments where status = :status").param("status", status)
                .query(Long.class).single();
    }

    public long failedRecurringRunsSince(OffsetDateTime since) {
        return jdbc.sql("select count(*) from recurring_runs where status = 'FAILED' and executed_at >= :since")
                .param("since", since).query(Long.class).single();
    }

    /** @param query matches part of the IBAN (spaces ignored) or of the owner's email, case-insensitive */
    public List<AccountRow> searchAccounts(String query, AccountStatus status, int limit, int offset) {
        Filter filter = filter(query, status);
        return jdbc.sql("""
                select a.id, a.iban, a.type, a.status, a.balance, a.created_at, u.email, u.first_name, u.last_name
                from accounts a join users u on u.id = a.user_id
                """ + filter.where + " order by a.created_at desc, a.id limit :limit offset :offset")
                .params(filter.params).param("limit", limit).param("offset", offset)
                .query((rs, i) -> new AccountRow(rs.getObject("id", UUID.class), rs.getString("iban"),
                        AccountType.valueOf(rs.getString("type")), AccountStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("balance").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        rs.getString("email"), rs.getString("first_name") + " " + rs.getString("last_name"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public long countAccounts(String query, AccountStatus status) {
        Filter filter = filter(query, status);
        return jdbc.sql("select count(*) from accounts a join users u on u.id = a.user_id " + filter.where)
                .params(filter.params).query(Long.class).single();
    }

    public void insertAction(UUID adminId, String action, UUID accountId, String reason, String detail,
            OffsetDateTime at) {
        jdbc.sql("""
                insert into admin_actions (admin_id, action, account_id, reason, detail, created_at)
                values (:admin, :action, :account, :reason, :detail, :at)
                """)
                .param("admin", adminId).param("action", action).param("account", accountId)
                .param("reason", reason).param("detail", detail).param("at", at).update();
    }

    public List<AuditEntry> recentActions(int limit) {
        return jdbc.sql("""
                select x.id, x.created_at, u.email admin_email, x.action, a.iban, x.reason, x.detail
                from admin_actions x join users u on u.id = x.admin_id left join accounts a on a.id = x.account_id
                order by x.created_at desc, x.id desc limit :limit
                """).param("limit", limit)
                .query((rs, i) -> new AuditEntry(rs.getLong("id"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("admin_email"),
                        rs.getString("action"), rs.getString("iban"), rs.getString("reason"),
                        rs.getString("detail")))
                .list();
    }

    private record Filter(String where, Map<String, Object> params) {
    }

    private static Filter filter(String query, AccountStatus status) {
        List<String> clauses = new ArrayList<>(List.of("a.type <> 'SYSTEM'"));
        Map<String, Object> params = new LinkedHashMap<>();
        if (status != null) {
            clauses.add("a.status = :status");
            params.put("status", status.name());
        }
        if (query != null && !query.isBlank()) {
            clauses.add("(a.iban ilike :ibanLike escape '\\' or u.email ilike :emailLike escape '\\')");
            params.put("ibanLike", "%" + escapeLike(query.replaceAll("\\s+", "")) + "%");
            params.put("emailLike", "%" + escapeLike(query.trim()) + "%");
        }
        return new Filter("where " + String.join(" and ", clauses), params);
    }

    /** A search for "50%" must find the text 50%, not everything starting with 50. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
