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
import java.util.Optional;
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
                select a.id, a.iban, a.type, a.status, a.balance, a.created_at, a.user_id, u.email, u.first_name, u.last_name
                from accounts a join users u on u.id = a.user_id
                """ + filter.where + " order by a.created_at desc, a.id limit :limit offset :offset")
                .params(filter.params).param("limit", limit).param("offset", offset)
                .query((rs, i) -> new AccountRow(rs.getObject("id", UUID.class), rs.getString("iban"),
                        AccountType.valueOf(rs.getString("type")), AccountStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("balance").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        rs.getString("email"), rs.getString("first_name") + " " + rs.getString("last_name"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("user_id", UUID.class)))
                .list();
    }

    public long countAccounts(String query, AccountStatus status) {
        Filter filter = filter(query, status);
        return jdbc.sql("select count(*) from accounts a join users u on u.id = a.user_id " + filter.where)
                .params(filter.params).query(Long.class).single();
    }

    /**
     * Customers whose login is locked right now. Lock records only hold a hash of the email, so a customer is found by
     * hashing the stored (already normalised) email the same way.
     */
    public List<AdminDtos.LockedLogin> lockedLogins(OffsetDateTime now) {
        return jdbc.sql("""
                select u.email, f.failures, f.locked_until
                from login_failures f join users u on encode(sha256(convert_to(u.email, 'UTF8')), 'hex') = f.email_hash
                where f.locked_until > :now order by f.locked_until desc, u.email
                """).param("now", now)
                .query((rs, i) -> new AdminDtos.LockedLogin(rs.getString("email"), rs.getInt("failures"),
                        rs.getObject("locked_until", OffsetDateTime.class).toInstant()))
                .list();
    }

    public long lockedAddressesWithoutCustomer(OffsetDateTime now) {
        return jdbc.sql("""
                select count(*) from login_failures f
                where f.locked_until > :now
                  and not exists (select 1 from users u where encode(sha256(convert_to(u.email, 'UTF8')), 'hex') = f.email_hash)
                """).param("now", now).query(Long.class).single();
    }

    /** @return 1 when an ACTIVE lock was removed, 0 when there was none (expired locks do not count) */
    public int unlockLogin(String emailHash, OffsetDateTime now) {
        return jdbc.sql("delete from login_failures where email_hash = :hash and locked_until > :now")
                .param("hash", emailHash).param("now", now).update();
    }

    /** Forgets a key's counters from {@code earliestStart} on, i.e. the previous and the current window. */
    public int deleteCounters(String bucketKey, OffsetDateTime earliestStart) {
        return jdbc.sql("delete from rate_limits where bucket_key = :key and window_start >= :earliest")
                .param("key", bucketKey).param("earliest", earliestStart).update();
    }

    public Optional<String> userEmail(UUID userId) {
        return jdbc.sql("select email from users where id = :id").param("id", userId).query(String.class).optional();
    }

    /** A customer (role CUSTOMER) by id: administrators are deliberately not returned. */
    public Optional<AdminDtos.CustomerProfile> findCustomer(UUID userId) {
        return jdbc.sql("select id, first_name, last_name, email, created_at from users where id = :id and role = 'CUSTOMER'")
                .param("id", userId)
                .query((rs, i) -> new AdminDtos.CustomerProfile(rs.getObject("id", UUID.class), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getString("email"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    public List<AdminDtos.CustomerAccount> customerAccounts(UUID userId) {
        return jdbc.sql("""
                select id, iban, type, status, nickname, balance, created_at from accounts
                where user_id = :user order by created_at, id
                """).param("user", userId)
                .query((rs, i) -> new AdminDtos.CustomerAccount(rs.getObject("id", UUID.class), rs.getString("iban"),
                        AccountType.valueOf(rs.getString("type")), AccountStatus.valueOf(rs.getString("status")),
                        rs.getString("nickname"), rs.getBigDecimal("balance").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** The newest ledger entries across all of the customer's accounts. */
    public List<AdminDtos.CustomerActivity> recentActivity(UUID userId, int limit) {
        return jdbc.sql("""
                select e.created_at, a.iban, t.type, e.amount, e.balance_after, t.description
                from ledger_entries e
                join accounts a on a.id = e.account_id
                join transactions t on t.id = e.transaction_id
                where a.user_id = :user order by e.created_at desc, e.id desc limit :limit
                """).param("user", userId).param("limit", limit)
                .query((rs, i) -> new AdminDtos.CustomerActivity(rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getString("iban"), com.bilal.neobank.ledger.TransactionType.valueOf(rs.getString("type")),
                        rs.getBigDecimal("amount").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        rs.getBigDecimal("balance_after").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        rs.getString("description")))
                .list();
    }

    public Optional<OffsetDateTime> loginLockedUntil(String emailHash, OffsetDateTime now) {
        return jdbc.sql("select locked_until from login_failures where email_hash = :hash and locked_until > :now")
                .param("hash", emailHash).param("now", now).query(OffsetDateTime.class).optional();
    }

    public long ordersOfUser(UUID userId, String status) {
        return jdbc.sql("select count(*) from recurring_payments where user_id = :user and status = :status")
                .param("user", userId).param("status", status).query(Long.class).single();
    }

    /** Closing an account ends the standing orders that pay out of it: they could never succeed again. */
    public int cancelOrdersOfAccount(UUID accountId) {
        return jdbc.sql("""
                update recurring_payments set status = 'CANCELLED', next_run_date = null
                where source_account_id = :account and status in ('ACTIVE', 'PAUSED')
                """).param("account", accountId).update();
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

    /**
     * Everything an administrator did that concerns this customer: actions on one of their accounts, plus the actions
     * that name them by email (viewing the customer, unlocking their login, clearing a probing block).
     */
    public List<AuditEntry> actionsAboutCustomer(UUID userId, String email, int limit) {
        return jdbc.sql("""
                select x.id, x.created_at, u.email admin_email, x.action, a.iban, x.reason, x.detail
                from admin_actions x join users u on u.id = x.admin_id left join accounts a on a.id = x.account_id
                where a.user_id = :user or x.detail in (:viewed, :named)
                order by x.created_at desc, x.id desc limit :limit
                """).param("user", userId).param("viewed", "email=" + email).param("named", "user=" + email)
                .param("limit", limit)
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
