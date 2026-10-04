package com.bilal.neobank.transfer;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.account.IbanGenerator;
import com.bilal.neobank.ledger.LedgerReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TransferApiTest {

    private static final String PASSWORD = "S3curepass12";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DatabaseCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerReconciliationService reconciliation;

    private final AtomicInteger keys = new AtomicInteger();

    @BeforeEach
    void clean() {
        cleaner.clean();
    }

    record Acc(String id, String iban) {
    }

    private String tokenFor(String email, String firstName, String lastName) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"%s","lastName":"%s","email":"%s","password":"%s"}
                """.formatted(firstName, lastName, email, PASSWORD))).andExpect(status().isCreated());
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private String alice() throws Exception {
        return tokenFor("alice@example.com", "Alice", "Kent");
    }

    private String bob() throws Exception {
        return tokenFor("bob@example.com", "Bob", "Miller");
    }

    private Acc open(String token, String type) throws Exception {
        String json = mockMvc.perform(post("/api/accounts").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"%s\"}".formatted(type)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Acc(json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"),
                json.replaceAll(".*\"iban\":\"([^\"]+)\".*", "$1"));
    }

    private void fund(String token, Acc account, String amount) throws Exception {
        mockMvc.perform(post("/api/accounts/" + account.id() + "/deposits").header("Authorization", token)
                .header("Idempotency-Key", "fund-" + keys.incrementAndGet() + "-xxxxxxxx")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"%s\"}".formatted(amount)))
                .andExpect(status().isCreated());
    }

    private ResultActions transfer(String token, String key, String sourceId, String iban, String amount)
            throws Exception {
        var request = post("/api/transfers").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId":"%s","destinationIban":"%s","amount":%s}
                        """.formatted(sourceId, iban, amount));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private ResultActions transfer(String token, Acc source, String iban, String amount) throws Exception {
        return transfer(token, "tr-" + keys.incrementAndGet() + "-xxxxxxxxxx", source.id(), iban, "\"" + amount + "\"");
    }

    private String balanceOf(String token, Acc account) throws Exception {
        String json = mockMvc.perform(get("/api/accounts/" + account.id()).header("Authorization", token))
                .andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"balance\":\"([^\"]+)\".*", "$1");
    }

    private void assertBooksBalance() {
        LedgerReconciliationService.Report report = reconciliation.check();
        assertThat(report.totalOfAllEntries()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.accountsWithWrongCachedBalance()).isEmpty();
        assertThat(report.customerAccountsBelowZero()).isEmpty();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    // ---- happy paths -------------------------------------------------------------------------------------------

    @Test
    void transferBetweenMyOwnAccountsMovesMoneyAndDoesNotUseTheDailyLimit() throws Exception {
        String token = alice();
        Acc checking = open(token, "CHECKING");
        Acc savings = open(token, "SAVINGS");
        fund(token, checking, "500.00");

        transfer(token, checking, savings.iban(), "200.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("200.00"))
                .andExpect(jsonPath("$.balance").value("300.00"))
                .andExpect(jsonPath("$.destinationIban").value(savings.iban()))
                .andExpect(jsonPath("$.sourceAccountId").value(checking.id()));

        assertThat(balanceOf(token, checking)).isEqualTo("300.00");
        assertThat(balanceOf(token, savings)).isEqualTo("200.00");
        assertThat(count("ledger_entries")).isEqualTo(4); // deposit (2) + transfer (2)
        mockMvc.perform(get("/api/transfers/limits").header("Authorization", token))
                .andExpect(jsonPath("$.usedToday").value("0.00"))
                .andExpect(jsonPath("$.remaining").value("5000.00"));
        assertBooksBalance();
    }

    @Test
    void transferToAnotherCustomerCreditsThemAndCountsTowardsTheDailyLimit() throws Exception {
        String alice = alice();
        String bob = bob();
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob, "CHECKING");
        fund(alice, aliceAcc, "500.00");

        transfer(alice, aliceAcc, bobAcc.iban(), "250.00").andExpect(status().isCreated());

        assertThat(balanceOf(alice, aliceAcc)).isEqualTo("250.00");
        assertThat(balanceOf(bob, bobAcc)).isEqualTo("250.00");
        mockMvc.perform(get("/api/transfers/limits").header("Authorization", alice))
                .andExpect(jsonPath("$.dailyLimit").value("5000.00"))
                .andExpect(jsonPath("$.usedToday").value("250.00"))
                .andExpect(jsonPath("$.remaining").value("4750.00"));
        mockMvc.perform(get("/api/transfers/limits").header("Authorization", bob))
                .andExpect(jsonPath("$.usedToday").value("0.00")); // receiving does not use my limit
        assertBooksBalance();
    }

    @Test
    void ibanIsAcceptedWithSpacesAndLowercase() throws Exception {
        String alice = alice();
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob(), "CHECKING");
        fund(alice, aliceAcc, "50.00");
        String pasted = bobAcc.iban().toLowerCase().replaceAll("(.{4})", "$1 ").trim();

        transfer(alice, aliceAcc, pasted, "10.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.destinationIban").value(bobAcc.iban()));
    }

    // ---- money rules -------------------------------------------------------------------------------------------

    @Test
    void overdraftIsRefusedAndNothingChanges() throws Exception {
        String alice = alice();
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob(), "CHECKING");
        fund(alice, aliceAcc, "30.00");
        int transactionsBefore = count("transactions");

        transfer(alice, aliceAcc, bobAcc.iban(), "30.01")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(balanceOf(alice, aliceAcc)).isEqualTo("30.00");
        assertThat(count("transactions")).isEqualTo(transactionsBefore);
        assertBooksBalance();
    }

    @Test
    void dailyLimitAppliesToOthersNotToMyOwnAccountsAndResetsEachUtcDay() throws Exception {
        String alice = alice();
        Acc checking = open(alice, "CHECKING");
        Acc savings = open(alice, "SAVINGS");
        Acc bobAcc = open(bob(), "CHECKING");
        fund(alice, checking, "10000.00");

        transfer(alice, checking, bobAcc.iban(), "3000.00").andExpect(status().isCreated());
        transfer(alice, checking, bobAcc.iban(), "2500.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.remaining").value("2000.00"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Remaining today: 2000.00")));
        transfer(alice, checking, bobAcc.iban(), "2000.00").andExpect(status().isCreated()); // exactly the limit
        transfer(alice, checking, bobAcc.iban(), "0.01").andExpect(status().isUnprocessableEntity());

        // moving money between my own accounts is not limited by the daily limit
        transfer(alice, checking, savings.iban(), "4000.00").andExpect(status().isCreated());

        // "tomorrow": pretend the transfers happened two days ago
        jdbc.update("update transactions set created_at = created_at - interval '2 days' where type = 'TRANSFER'");
        mockMvc.perform(get("/api/transfers/limits").header("Authorization", alice))
                .andExpect(jsonPath("$.usedToday").value("0.00"));
        transfer(alice, checking, bobAcc.iban(), "1000.00").andExpect(status().isCreated());
        assertBooksBalance();
    }

    // ---- idempotency -------------------------------------------------------------------------------------------

    @Test
    void replayReturns200WithTheOriginalResultAndMovesMoneyOnce() throws Exception {
        String alice = alice();
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob(), "CHECKING");
        fund(alice, aliceAcc, "100.00");

        String first = transfer(alice, "replay-key-0001", aliceAcc.id(), bobAcc.iban(), "\"40.00\"")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = transfer(alice, "replay-key-0001", aliceAcc.id(), bobAcc.iban(), "\"40.00\"")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(balanceOf(alice, aliceAcc)).isEqualTo("60.00");
        assertThat(count("transactions")).isEqualTo(2); // the funding deposit and ONE transfer

        transfer(alice, "replay-key-0001", aliceAcc.id(), bobAcc.iban(), "\"41.00\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertBooksBalance();
    }

    // ---- validation and ownership ------------------------------------------------------------------------------

    @Test
    void cannotSpendFromSomeoneElsesAccount() throws Exception {
        String alice = alice();
        String bob = bob();
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob, "CHECKING");
        fund(bob, bobAcc, "100.00");

        transfer(alice, bobAcc, aliceAcc.iban(), "10.00").andExpect(status().isNotFound());
        transfer(alice, "key-unknown-src-1", UUID.randomUUID().toString(), aliceAcc.iban(), "\"1.00\"")
                .andExpect(status().isNotFound());
        assertThat(balanceOf(bob, bobAcc)).isEqualTo("100.00");
    }

    @Test
    void badIbansAreRejectedWithDistinctErrors() throws Exception {
        String alice = alice();
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob(), "CHECKING");
        fund(alice, aliceAcc, "100.00");
        String typo = bobAcc.iban().substring(0, bobAcc.iban().length() - 1)
                + (bobAcc.iban().endsWith("9") ? "8" : "9");

        transfer(alice, aliceAcc, typo, "10.00").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IBAN"));
        transfer(alice, aliceAcc, "SYSTEM-FUNDING", "10.00").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IBAN"));
        transfer(alice, aliceAcc, IbanGenerator.generate(9999999999L), "10.00").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_FOUND"));
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test
    void cannotTransferToTheSameAccount() throws Exception {
        String alice = alice();
        Acc aliceAcc = open(alice, "CHECKING");
        fund(alice, aliceAcc, "100.00");

        transfer(alice, aliceAcc, aliceAcc.iban(), "10.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
    }

    @Test
    void invalidAmountsMissingKeyAndMissingTokenAreRejected() throws Exception {
        String alice = alice();
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob(), "CHECKING");
        fund(alice, aliceAcc, "100.00");

        for (String amount : List.of("\"0\"", "\"-1.00\"", "\"1.005\"", "\"10000.01\"", "\"abc\"", "null")) {
            transfer(alice, "key-bad-" + Math.abs(amount.hashCode()) + "-xx", aliceAcc.id(),
                    bobAcc.iban(), amount).andExpect(status().isBadRequest());
        }
        transfer(alice, null, aliceAcc.id(), bobAcc.iban(), "\"1.00\"")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", "key-no-token-1")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test
    void frozenSourceAndFrozenRecipientAreRejectedWithDifferentCodes() throws Exception {
        String alice = alice();
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob(), "CHECKING");
        fund(alice, aliceAcc, "100.00");

        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", bobAcc.id());
        transfer(alice, aliceAcc, bobAcc.iban(), "10.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECIPIENT_UNAVAILABLE"));

        jdbc.update("update accounts set status = 'ACTIVE' where id = ?::uuid", bobAcc.id());
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", aliceAcc.id());
        transfer(alice, aliceAcc, bobAcc.iban(), "10.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        assertBooksBalance();
    }

    // ---- recipient lookup --------------------------------------------------------------------------------------

    @Test
    void lookupShowsOnlyFirstNameAndLastInitial() throws Exception {
        String alice = alice();
        Acc bobAcc = open(bob(), "CHECKING");

        mockMvc.perform(get("/api/recipients/lookup").param("iban", bobAcc.iban().toLowerCase())
                .header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerName").value("Bob M."))
                .andExpect(jsonPath("$.iban").value(bobAcc.iban()))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void lookupRejectsBadUnknownAndFrozenIbansAndNeedsAToken() throws Exception {
        String alice = alice();
        Acc bobAcc = open(bob(), "CHECKING");

        mockMvc.perform(get("/api/recipients/lookup").param("iban", "XX00NEOB123").header("Authorization", alice))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IBAN"));
        mockMvc.perform(get("/api/recipients/lookup").param("iban", IbanGenerator.generate(9999999999L))
                .header("Authorization", alice))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECIPIENT_NOT_FOUND"));
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", bobAcc.id());
        mockMvc.perform(get("/api/recipients/lookup").param("iban", bobAcc.iban()).header("Authorization", alice))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/recipients/lookup").param("iban", bobAcc.iban())).andExpect(status().isUnauthorized());
    }

    // ---- concurrency -------------------------------------------------------------------------------------------

    @Test
    void crossingTransfersBetweenTwoUsersNeverDeadlockAndConserveMoney() throws Exception {
        String alice = alice();
        String bob = bob();
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob, "CHECKING");
        fund(alice, aliceAcc, "1000.00");
        fund(bob, bobAcc, "1000.00");

        List<Integer> statuses = assertTimeoutPreemptively(Duration.ofSeconds(90), () -> runConcurrently(40, i ->
                i % 2 == 0
                        ? transfer(alice, aliceAcc, bobAcc.iban(), "10.00").andReturn().getResponse().getStatus()
                        : transfer(bob, bobAcc, aliceAcc.iban(), "10.00").andReturn().getResponse().getStatus()));

        assertThat(statuses).containsOnly(201);
        assertThat(balanceOf(alice, aliceAcc)).isEqualTo("1000.00"); // 20 out, 20 in
        assertThat(balanceOf(bob, bobAcc)).isEqualTo("1000.00");
        assertBooksBalance();
    }

    @Test
    void concurrentTransfersCannotExceedTheDailyLimit() throws Exception {
        String alice = alice();
        Acc aliceAcc = open(alice, "CHECKING");
        String bob = bob();
        Acc bobAcc = open(bob, "CHECKING");
        fund(alice, aliceAcc, "10000.00");

        List<Integer> statuses = runConcurrently(10,
                i -> transfer(alice, aliceAcc, bobAcc.iban(), "1000.00").andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5); // limit 5000 / 1000 each
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(5);
        assertThat(balanceOf(bob, bobAcc)).isEqualTo("5000.00");
        assertBooksBalance();
    }

    @Test
    void concurrentTransfersCannotOverdrawAnAccount() throws Exception {
        String alice = alice();
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob(), "CHECKING");
        fund(alice, aliceAcc, "100.00");

        List<Integer> statuses = runConcurrently(2,
                i -> transfer(alice, aliceAcc, bobAcc.iban(), "80.00").andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(balanceOf(alice, aliceAcc)).isEqualTo("20.00");
        assertBooksBalance();
    }

    @FunctionalInterface
    private interface Call<T> {
        T run(int index) throws Exception;
    }

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
