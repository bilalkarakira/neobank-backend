package com.bilal.neobank.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Recomputes balances from the ledger and checks the books balance. The ledger is the source of truth. */
@Service
public class LedgerReconciliationService {

    public record Report(BigDecimal totalOfAllEntries, List<UUID> accountsWithWrongCachedBalance,
            List<UUID> customerAccountsBelowZero, List<UUID> accountsHeldAboveBalance) {

        public boolean isHealthy() {
            return totalOfAllEntries.signum() == 0 && accountsWithWrongCachedBalance.isEmpty()
                    && customerAccountsBelowZero.isEmpty() && accountsHeldAboveBalance.isEmpty();
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LedgerReconciliationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Report check() {
        BigDecimal total = jdbc.queryForObject("select coalesce(sum(amount), 0) from ledger_entries", BigDecimal.class);

        List<UUID> wrongBalance = jdbc.query("""
                select a.id from accounts a
                left join ledger_entries e on e.account_id = a.id
                group by a.id, a.balance
                having a.balance <> coalesce(sum(e.amount), 0)
                """, (rs, i) -> rs.getObject(1, UUID.class));

        List<UUID> negative = jdbc.query("select id from accounts where balance < 0 and type <> 'SYSTEM'",
                (rs, i) -> rs.getObject(1, UUID.class));

        // card holds must always be covered by the balance: available money is never negative
        List<UUID> overheld = jdbc.query("""
                select a.id from accounts a
                join (select account_id, sum(amount) held from authorizations
                      where status = 'AUTHORIZED' and expires_at > ? group by account_id) h on h.account_id = a.id
                where h.held > a.balance and a.type <> 'SYSTEM'
                """, (rs, i) -> rs.getObject(1, UUID.class), Timestamp.from(clock.instant()));

        return new Report(total, wrongBalance, negative, overheld);
    }
}
