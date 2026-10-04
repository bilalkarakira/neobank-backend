package com.bilal.neobank.analytics;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Categories end to end: data created ONLY through the real deposit and transfer APIs, then read back. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CategoryApiTest extends AnalyticsTestBase {

    private ResultActions deposit(String token, Acc account, String amount, String key) throws Exception {
        return mockMvc.perform(post("/api/accounts/" + account.id() + "/deposits").header("Authorization", token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"%s\"}".formatted(amount)));
    }

    private ResultActions transfer(String token, Acc from, String iban, String amount, String key, String categoryId)
            throws Exception {
        String category = categoryId == null ? "null" : "\"" + categoryId + "\"";
        return mockMvc.perform(post("/api/transfers").header("Authorization", token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceAccountId":"%s","destinationIban":"%s","amount":"%s","categoryId":%s}
                        """.formatted(from.id(), iban, amount, category)));
    }

    @Test
    void listsTheFixedCategoriesInOrder() throws Exception {
        String alice = tokenFor("alice@example.com");

        api(alice, "/api/categories")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].name").value("Groceries"))
                .andExpect(jsonPath("$[9].name").value("Other"))
                .andExpect(jsonPath("$[0].id").isNotEmpty());
        mockMvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCategoryIsStoredOnTransfersToOthersAndFlowsIntoAnalyticsAndHistory() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(bob, "CHECKING");
        deposit(alice, aliceAcc, "500.00", "cat-deposit-0001").andExpect(status().isCreated());

        transfer(alice, aliceAcc, bobAcc.iban(), "42.50", "cat-transfer-0001", categoryId("Groceries").toString())
                .andExpect(status().isCreated());

        // analytics sees it ...
        api(alice, "/api/analytics/spending-by-category")
                .andExpect(jsonPath("$.categories[0].name").value("Groceries"))
                .andExpect(jsonPath("$.categories[0].total").value("42.50"))
                .andExpect(jsonPath("$.categories[0].share").value(100.0));
        api(alice, "/api/analytics/cashflow?months=1")
                .andExpect(jsonPath("$[0].income").value("500.00"))
                .andExpect(jsonPath("$[0].spending").value("42.50"));
        // ... the last balance point equals the real balance (data created through the real APIs) ...
        api(alice, "/api/analytics/balance-history?days=7&accountId=" + aliceAcc.id())
                .andExpect(jsonPath("$[6].balance").value("457.50"));
        api(alice, "/api/accounts/" + aliceAcc.id()).andExpect(jsonPath("$.balance").value("457.50"));
        // ... the sender's history shows the category, the receiver's does not
        api(alice, "/api/accounts/" + aliceAcc.id() + "/transactions?type=TRANSFER")
                .andExpect(jsonPath("$.items[0].category").value("Groceries"));
        api(bob, "/api/accounts/" + bobAcc.id() + "/transactions")
                .andExpect(jsonPath("$.items[0].direction").value("IN"))
                .andExpect(jsonPath("$.items[0].category").doesNotExist());
        // ... and Bob's own spending view stays empty: receiving money is not spending
        api(bob, "/api/analytics/spending-by-category").andExpect(jsonPath("$.categories.length()").value(0));
        assertBooksBalance();
    }

    @Test
    void aCategoryIsIgnoredWhenMovingMoneyBetweenMyOwnAccounts() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc checking = open(alice, "CHECKING");
        Acc savings = open(alice, "SAVINGS");
        deposit(alice, checking, "200.00", "cat-deposit-0002").andExpect(status().isCreated());

        transfer(alice, checking, savings.iban(), "50.00", "cat-transfer-0002", categoryId("Housing").toString())
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select count(*) from transactions where category_id is not null",
                Integer.class)).isZero();
        api(alice, "/api/analytics/spending-by-category").andExpect(jsonPath("$.categories.length()").value(0));
    }

    @Test
    void anUnknownCategoryIsRejectedAndNothingMoves() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(tokenFor("bob@example.com"), "CHECKING");
        deposit(alice, aliceAcc, "100.00", "cat-deposit-0003").andExpect(status().isCreated());

        transfer(alice, aliceAcc, bobAcc.iban(), "10.00", "cat-transfer-0003", UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        api(alice, "/api/accounts/" + aliceAcc.id()).andExpect(jsonPath("$.balance").value("100.00"));
    }

    @Test
    void theCategoryIsPartOfTheIdempotencyFingerprint() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(tokenFor("bob@example.com"), "CHECKING");
        deposit(alice, aliceAcc, "100.00", "cat-deposit-0004").andExpect(status().isCreated());
        String dining = categoryId("Dining").toString();

        transfer(alice, aliceAcc, bobAcc.iban(), "10.00", "cat-transfer-0004", dining).andExpect(status().isCreated());
        transfer(alice, aliceAcc, bobAcc.iban(), "10.00", "cat-transfer-0004", dining).andExpect(status().isOk());
        transfer(alice, aliceAcc, bobAcc.iban(), "10.00", "cat-transfer-0004", categoryId("Health").toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        transfer(alice, aliceAcc, bobAcc.iban(), "10.00", "cat-transfer-0004", null)
                .andExpect(status().isUnprocessableEntity());

        api(alice, "/api/accounts/" + aliceAcc.id()).andExpect(jsonPath("$.balance").value("90.00"));
    }

    @Test
    void transfersWithoutACategoryStillWorkAndAreUncategorized() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc aliceAcc = open(alice, "CHECKING");
        Acc bobAcc = open(tokenFor("bob@example.com"), "CHECKING");
        deposit(alice, aliceAcc, "100.00", "cat-deposit-0005").andExpect(status().isCreated());

        // the old request shape (no categoryId field at all) keeps working
        mockMvc.perform(post("/api/transfers").header("Authorization", alice).header("Idempotency-Key", "cat-transfer-0005")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceAccountId":"%s","destinationIban":"%s","amount":"5.00"}
                        """.formatted(aliceAcc.id(), bobAcc.iban()))).andExpect(status().isCreated());

        api(alice, "/api/analytics/spending-by-category")
                .andExpect(jsonPath("$.categories[0].name").value("Uncategorized"));
    }
}
