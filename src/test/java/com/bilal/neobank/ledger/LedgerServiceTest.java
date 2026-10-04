package com.bilal.neobank.ledger;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.user.Role;
import com.bilal.neobank.user.User;
import com.bilal.neobank.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Direct tests of the ledger rules; transfers (F6) will be built on exactly this method. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerServiceTest {

    @Autowired
    LedgerService ledger;

    @Autowired
    UserRepository userRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    LedgerReconciliationService reconciliation;

    @Autowired
    DatabaseCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    private User user;
    private Account payer;
    private Account payee;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        user = userRepository.save(new User("ledger@example.com", "hash", "Led", "Ger", Role.CUSTOMER));
        payer = accountRepository.save(new Account(user.getId(), "XX00NEOBTEST000001", AccountType.CHECKING, null));
        payee = accountRepository.save(new Account(user.getId(), "XX00NEOBTEST000002", AccountType.SAVINGS, null));
    }

    private void fund(Account account, String amount) {
        BigDecimal value = new BigDecimal(amount);
        ledger.post(TransactionType.DEPOSIT, user.getId(), "fund-" + account.getId(), "h", null,
                List.of(new Posting(SystemAccounts.FUNDING, value.negate()), new Posting(account.getId(), value)));
    }

    private BigDecimal balance(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    @Test
    void rejectsPostingsThatDoNotSumToZero() {
        assertThatThrownBy(() -> ledger.post(TransactionType.DEPOSIT, user.getId(), "k1", "h", null,
                List.of(new Posting(SystemAccounts.FUNDING, new BigDecimal("-10.00")),
                        new Posting(payer.getId(), new BigDecimal("11.00")))))
                .isInstanceOf(UnbalancedPostingException.class);

        assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isZero();
    }

    @Test
    void rejectsSingleAccountAndZeroAmountPostings() {
        assertThatThrownBy(() -> ledger.post(TransactionType.DEPOSIT, user.getId(), "k1", "h", null,
                List.of(new Posting(payer.getId(), BigDecimal.ZERO), new Posting(SystemAccounts.FUNDING, BigDecimal.ZERO))))
                .isInstanceOf(UnbalancedPostingException.class);
        assertThatThrownBy(() -> ledger.post(TransactionType.DEPOSIT, user.getId(), "k2", "h", null,
                List.of(new Posting(payer.getId(), new BigDecimal("5")), new Posting(payer.getId(), new BigDecimal("-5")))))
                .isInstanceOf(UnbalancedPostingException.class);
    }

    @Test
    void movesMoneyBetweenCustomerAccountsAndKeepsBooksBalanced() {
        fund(payer, "100.00");

        ledger.post(TransactionType.TRANSFER, user.getId(), "t1", "h", "rent",
                List.of(new Posting(payer.getId(), new BigDecimal("-40.00")),
                        new Posting(payee.getId(), new BigDecimal("40.00"))));

        assertThat(balance(payer)).isEqualByComparingTo("60.00");
        assertThat(balance(payee)).isEqualByComparingTo("40.00");
        assertThat(reconciliation.check().isHealthy()).isTrue();
    }

    @Test
    void refusesToOverdrawACustomerAccountAndRollsEverythingBack() {
        fund(payer, "30.00");

        assertThatThrownBy(() -> ledger.post(TransactionType.TRANSFER, user.getId(), "t1", "h", null,
                List.of(new Posting(payer.getId(), new BigDecimal("-30.01")),
                        new Posting(payee.getId(), new BigDecimal("30.01")))))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(payer)).isEqualByComparingTo("30.00");
        assertThat(balance(payee)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isEqualTo(1); // only the funding
        assertThat(reconciliation.check().isHealthy()).isTrue();
    }

    @Test
    void reconciliationDetectsACachedBalanceThatDriftedFromTheLedger() {
        fund(payer, "100.00");
        assertThat(reconciliation.check().isHealthy()).isTrue();

        jdbc.update("update accounts set balance = 999 where id = ?::uuid", payer.getId().toString());

        LedgerReconciliationService.Report report = reconciliation.check();
        assertThat(report.isHealthy()).isFalse();
        assertThat(report.accountsWithWrongCachedBalance()).containsExactly(payer.getId());
    }
}
