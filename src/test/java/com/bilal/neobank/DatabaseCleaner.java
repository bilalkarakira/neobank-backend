package com.bilal.neobank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resets the database between integration tests. TRUNCATE is used for the ledger because its append-only trigger
 * (correctly) refuses DELETE; the seeded bank funding account is kept and zeroed.
 */
@Component
public class DatabaseCleaner {

    private final JdbcTemplate jdbc;

    public DatabaseCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clean() {
        jdbc.execute("TRUNCATE card_disputes, dispute_files, card_receivables, clearing_items, settlement_runs, clearing_files, authorizations, cards, rate_limits, login_failures, refresh_tokens, admin_actions, recurring_runs, recurring_payments, interest_accruals, ledger_entries, transactions");
        jdbc.update("DELETE FROM accounts WHERE type <> 'SYSTEM'");
        jdbc.update("UPDATE accounts SET balance = 0 WHERE type = 'SYSTEM'");
        jdbc.update("DELETE FROM users");
    }
}
