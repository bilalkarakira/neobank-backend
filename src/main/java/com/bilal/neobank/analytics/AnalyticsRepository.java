package com.bilal.neobank.analytics;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Aggregations over the ledger. Callers always pass the exact set of the user's own account ids to look at, and the
 * user's id, which is how "money moved between my own accounts" is recognised and kept out of income and spending.
 */
@Repository
public class AnalyticsRepository {

    public record DailyBalance(LocalDate date, BigDecimal balance) {
    }

    public record MonthFlow(String month, BigDecimal income, BigDecimal spending) {
    }

    public record CategorySpend(String name, BigDecimal total) {
    }

    private final JdbcClient jdbc;

    public AnalyticsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal totalBalance(Collection<UUID> accountIds) {
        return jdbc.sql("select coalesce(sum(balance), 0) from accounts where id in (:ids)")
                .param("ids", accountIds).query(BigDecimal.class).single();
    }

    /**
     * End-of-day balance for every day from {@code start} to {@code end} (UTC, inclusive), summed over the accounts.
     * For each account and day, the balance is the {@code balance_after} of its last entry up to the end of that day,
     * so days without activity carry the previous balance forward and the first day starts from the true balance
     * (an account with no entry yet counts as zero).
     */
    public List<DailyBalance> dailyBalances(Collection<UUID> accountIds, LocalDate start, LocalDate end) {
        return jdbc.sql("""
                select d.day::date as day, coalesce(sum(b.balance_after), 0) as balance
                from generate_series(cast(:start as date), cast(:end as date), interval '1 day') as d(day)
                cross join (select id from accounts where id in (:ids)) a
                left join lateral (
                    select e.balance_after from ledger_entries e
                    where e.account_id = a.id
                      and e.created_at < (((d.day::date + 1))::timestamp at time zone 'UTC')
                    order by e.created_at desc, e.id desc
                    limit 1
                ) b on true
                group by d.day
                order by d.day
                """)
                .param("start", start).param("end", end).param("ids", accountIds)
                .query((rs, i) -> new DailyBalance(rs.getObject("day", LocalDate.class), rs.getBigDecimal("balance")))
                .list();
    }

    /**
     * Money in and out per UTC calendar month. Transfers between the user's own accounts are neither income nor
     * spending, so they are excluded (in every scope, including a single account).
     */
    public List<MonthFlow> monthlyFlow(Collection<UUID> accountIds, UUID userId, Instant from, Instant toExclusive) {
        return jdbc.sql("""
                select to_char(date_trunc('month', e.created_at at time zone 'UTC'), 'YYYY-MM') as month,
                       coalesce(sum(case when e.amount > 0 then e.amount end), 0) as income,
                       coalesce(-sum(case when e.amount < 0 then e.amount end), 0) as spending
                from ledger_entries e
                join transactions t on t.id = e.transaction_id
                join accounts cp on cp.id = case when e.amount < 0 then t.destination_account_id
                                                 else t.source_account_id end
                where e.account_id in (:ids)
                  and e.created_at >= :from and e.created_at < :to
                  and cp.user_id is distinct from :userId
                group by 1
                order by 1
                """)
                .param("ids", accountIds).param("userId", userId)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC))
                .query((rs, i) -> new MonthFlow(rs.getString("month"), rs.getBigDecimal("income"),
                        rs.getBigDecimal("spending")))
                .list();
    }

    /** Outgoing transfers to OTHER customers, grouped by the sender's category (null category = Uncategorized). */
    public List<CategorySpend> spendingByCategory(Collection<UUID> accountIds, UUID userId, Instant from,
            Instant toExclusive) {
        return jdbc.sql("""
                select coalesce(cat.name, 'Uncategorized') as name, -sum(e.amount) as total
                from ledger_entries e
                join transactions t on t.id = e.transaction_id
                join accounts cp on cp.id = t.destination_account_id
                left join categories cat on cat.id = t.category_id
                where e.account_id in (:ids) and e.amount < 0 and t.type = 'TRANSFER'
                  and e.created_at >= :from and e.created_at < :to
                  and cp.user_id is distinct from :userId
                group by cat.name
                order by total desc, name
                """)
                .param("ids", accountIds).param("userId", userId)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC))
                .query((rs, i) -> new CategorySpend(rs.getString("name"), rs.getBigDecimal("total")))
                .list();
    }
}
