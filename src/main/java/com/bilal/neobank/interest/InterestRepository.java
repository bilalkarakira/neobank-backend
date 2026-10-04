package com.bilal.neobank.interest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class InterestRepository {

    public record Accrual(String period, AccrualStatus status, BigDecimal averageBalance, BigDecimal monthlyRate,
            BigDecimal amount) {
    }

    private final JdbcClient jdbc;

    public InterestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(UUID accountId, String period) {
        return jdbc.sql("select count(*) from interest_accruals where account_id = :a and period = :p")
                .param("a", accountId).param("p", period).query(Long.class).single() > 0;
    }

    public Set<String> processedPeriods(UUID accountId) {
        return new HashSet<>(jdbc.sql("select period from interest_accruals where account_id = :a")
                .param("a", accountId).query(String.class).list());
    }

    /** The primary key (account, period) is the last line of defence against crediting a month twice. */
    public void insert(UUID accountId, String period, AccrualStatus status, BigDecimal averageBalance,
            BigDecimal monthlyRate, BigDecimal amount, UUID transactionId, OffsetDateTime processedAt) {
        jdbc.sql("""
                insert into interest_accruals (account_id, period, status, average_balance, monthly_rate, amount,
                                               transaction_id, processed_at)
                values (:account, :period, :status, :average, :rate, :amount, :tx, :at)
                """)
                .param("account", accountId).param("period", period).param("status", status.name())
                .param("average", averageBalance).param("rate", monthlyRate).param("amount", amount)
                .param("tx", transactionId).param("at", processedAt)
                .update();
    }

    public List<Accrual> list(UUID accountId) {
        return jdbc.sql("""
                select period, status, average_balance, monthly_rate, amount
                from interest_accruals where account_id = :a order by period desc
                """)
                .param("a", accountId)
                .query((rs, i) -> new Accrual(rs.getString("period"), AccrualStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("average_balance"), rs.getBigDecimal("monthly_rate"),
                        rs.getBigDecimal("amount")))
                .list();
    }

    /** Interest credited for months of the given calendar year (attributed by the month it was earned in). */
    public BigDecimal earnedForYear(UUID accountId, int year) {
        return jdbc.sql("""
                select coalesce(sum(amount), 0) from interest_accruals
                where account_id = :a and status = 'CREDITED' and period like :year
                """)
                .param("a", accountId).param("year", year + "-%").query(BigDecimal.class).single();
    }
}
