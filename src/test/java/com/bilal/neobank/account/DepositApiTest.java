package com.bilal.neobank.account;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.ledger.LedgerReconciliationService;
import com.bilal.neobank.ledger.SystemAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DepositApiTest {

    private static final String PASSWORD = "S3curepass12";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DatabaseCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerReconciliationService reconciliation;

    @BeforeEach
    void clean() {
        cleaner.clean();
    }

    private String tokenFor(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Test","lastName":"User","email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andExpect(status().isCreated());
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private String openAccount(String token, String type) throws Exception {
        String json = mockMvc.perform(post("/api/accounts").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"%s\"}".formatted(type)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions deposit(String token, String accountId, String key, String amount) throws Exception {
        var request = post("/api/accounts/" + accountId + "/deposits").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":%s}".formatted(amount));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private String balanceOf(String token, String accountId) throws Exception {
        String json = mockMvc.perform(get("/api/accounts/" + accountId).header("Authorization", token))
                .andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"balance\":\"([^\"]+)\".*", "$1");
    }

    private void assertBooksBalance() {
        LedgerReconciliationService.Report report = reconciliation.check();
        assertThat(report.totalOfAllEntries()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.accountsWithWrongCachedBalance()).isEmpty();
        assertThat(report.customerAccountsBelowZero()).isEmpty();
    }

    @Test
    void depositCreditsAccountAndWritesTwoBalancedEntries() throws Exception {
        String token = tokenFor("a@example.com");
        String account = openAccount(token, "CHECKING");

        deposit(token, account, "key-00000001", "\"100.00\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("100.00"))
                .andExpect(jsonPath("$.balance").value("100.00"))
                .andExpect(jsonPath("$.accountId").value(account));

        assertThat(balanceOf(token, account)).isEqualTo("100.00");
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select balance from accounts where id = ?::uuid", BigDecimal.class,
                SystemAccounts.FUNDING.toString())).isEqualByComparingTo("-100.00");
        assertBooksBalance();
    }

    @Test
    void balanceAfterReflectsRunningBalance() throws Exception {
        String token = tokenFor("a@example.com");
        String account = openAccount(token, "SAVINGS");

        deposit(token, account, "key-00000001", "50.25").andExpect(jsonPath("$.balance").value("50.25"));
        deposit(token, account, "key-00000002", "\"0.75\"").andExpect(jsonPath("$.balance").value("51.00"));
        assertBooksBalance();
    }

    @Test
    void replayingTheSameKeyAndBodyReturns200WithTheOriginalResultAndMovesMoneyOnce() throws Exception {
        String token = tokenFor("a@example.com");
        String account = openAccount(token, "CHECKING");

        String first = deposit(token, account, "key-00000001", "\"100.00\"").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        deposit(token, account, "key-00000002", "\"25.00\"").andExpect(status().isCreated());

        String replay = deposit(token, account, "key-00000001", "\"100.00\"").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first); // identical, including the balance at the time (100.00, not 125.00)
        assertThat(balanceOf(token, account)).isEqualTo("125.00");
        assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isEqualTo(2);
        assertBooksBalance();
    }

    @Test
    void reusingAKeyWithADifferentAmountIsRejected() throws Exception {
        String token = tokenFor("a@example.com");
        String account = openAccount(token, "CHECKING");

        deposit(token, account, "key-00000001", "\"100.00\"").andExpect(status().isCreated());
        deposit(token, account, "key-00000001", "\"200.00\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(balanceOf(token, account)).isEqualTo("100.00");
    }

    @Test
    void twentyConcurrentDepositsWithDifferentKeysAllLand() throws Exception {
        String token = tokenFor("a@example.com");
        String account = openAccount(token, "CHECKING");

        List<Integer> statuses = runConcurrently(20, i -> deposit(token, account, "key-concurrent-" + i, "\"10.00\"")
                .andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(201);
        assertThat(balanceOf(token, account)).isEqualTo("200.00");
        assertBooksBalance();
    }

    @Test
    void tenConcurrentRequestsWithTheSameKeyCreditOnlyOnce() throws Exception {
        String token = tokenFor("a@example.com");
        String account = openAccount(token, "CHECKING");

        List<Integer> statuses = runConcurrently(10, i -> deposit(token, account, "key-same-for-all", "\"10.00\"")
                .andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200, 201);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(balanceOf(token, account)).isEqualTo("10.00");
        assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isEqualTo(1);
        assertBooksBalance();
    }

    @Test
    void invalidAmountsAreRejectedWithoutChangingAnything() throws Exception {
        String token = tokenFor("a@example.com");
        String account = openAccount(token, "CHECKING");

        for (String amount : List.of("\"0\"", "\"0.00\"", "\"-5.00\"", "\"1.005\"", "\"10000.01\"", "\"abc\"", "null")) {
            deposit(token, account, "key-invalid-" + Math.abs(amount.hashCode()), amount)
                    .andExpect(status().isBadRequest());
        }
        deposit(token, account, "key-00000009", "\"10000.00\"").andExpect(status().isCreated()); // the maximum is allowed

        assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isEqualTo(1);
        assertBooksBalance();
    }

    @Test
    void missingOrMalformedIdempotencyKeyIsRejected() throws Exception {
        String token = tokenFor("a@example.com");
        String account = openAccount(token, "CHECKING");

        deposit(token, account, null, "\"10.00\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        deposit(token, account, "short", "\"10.00\"").andExpect(status().isBadRequest());
    }

    @Test
    void cannotDepositIntoSomeoneElsesAccountOrAnUnknownOne() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        String bobsAccount = openAccount(bob, "CHECKING");

        deposit(alice, bobsAccount, "key-00000001", "\"10.00\"").andExpect(status().isNotFound());
        deposit(alice, UUID.randomUUID().toString(), "key-00000002", "\"10.00\"").andExpect(status().isNotFound());
        assertThat(balanceOf(bob, bobsAccount)).isEqualTo("0.00");
    }

    @Test
    void cannotDepositIntoTheBankFundingAccount() throws Exception {
        String token = tokenFor("a@example.com");

        deposit(token, SystemAccounts.FUNDING.toString(), "key-00000001", "\"10.00\"").andExpect(status().isNotFound());
    }

    @Test
    void frozenAccountRejectsDeposits() throws Exception {
        String token = tokenFor("a@example.com");
        String account = openAccount(token, "CHECKING");
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", account);

        deposit(token, account, "key-00000001", "\"10.00\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        assertBooksBalance();
    }

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/accounts/" + UUID.randomUUID() + "/deposits")
                .header("Idempotency-Key", "key-00000001").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"1.00\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void customersCannotOpenSystemAccounts() throws Exception {
        String token = tokenFor("a@example.com");

        mockMvc.perform(post("/api/accounts").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SYSTEM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_TYPE_NOT_ALLOWED"));
    }

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() throws Exception {
        String token = tokenFor("a@example.com");
        deposit(token, openAccount(token, "CHECKING"), "key-00000001", "\"10.00\"");

        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount = 999"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertBooksBalance();
    }

    @FunctionalInterface
    private interface Call<T> {
        T run(int index) throws Exception;
    }

    /** Starts n threads that all fire at the same moment, and returns their results. */
    private <T> List<T> runConcurrently(int n, Call<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                return call.run(index);
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();
        return results;
    }
}
