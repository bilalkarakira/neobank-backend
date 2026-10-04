package com.bilal.neobank.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Recomputes balances from the ledger and checks the books balance. The ledger is the source of truth. */
@Service
public class LedgerReconciliationService {

    public record Report(BigDecimal totalOfAllEntries, List<UUID> accountsWithWrongCachedBalance,
            List<UUID> customerAccountsBelowZero) {

        public boolean isHealthy() {
            return totalOfAllEntries.signum() == 0 && accountsWithWrongCachedBalance.isEmpty()
                    && customerAccountsBelowZero.isEmpty();
        }
    }

    private final JdbcTemplate jdbc;

    public LedgerReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

        return new Report(total, wrongBalance, negative);
    }
}
