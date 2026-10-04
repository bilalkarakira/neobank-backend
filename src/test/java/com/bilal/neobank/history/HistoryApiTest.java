package com.bilal.neobank.history;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HistoryApiTest extends HistoryTestBase {

    // ---- content ------------------------------------------------------------------------------------------------

    @Test
    void listsNewestFirstWithSignedAmountsAndAConsistentRunningBalance() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc aliceAcc = open(alice, "CHECKING", null);
        Acc bobAcc = open(tokenFor("bob@example.com", "Bob", "Miller"), "CHECKING", null);

        deposit(alice, aliceAcc, "100.00", "first");
        transfer(alice, aliceAcc, bobAcc.iban(), "30.00", "second");
        deposit(alice, aliceAcc, "5.00", "third");

        history(alice, aliceAcc, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].description").value("third"))
                .andExpect(jsonPath("$.items[0].amount").value("5.00"))
                .andExpect(jsonPath("$.items[0].direction").value("IN"))
                .andExpect(jsonPath("$.items[0].balanceAfter").value("75.00"))
                .andExpect(jsonPath("$.items[1].amount").value("-30.00"))
                .andExpect(jsonPath("$.items[1].direction").value("OUT"))
                .andExpect(jsonPath("$.items[1].type").value("TRANSFER"))
                .andExpect(jsonPath("$.items[1].balanceAfter").value("70.00"))
                .andExpect(jsonPath("$.items[2].amount").value("100.00"))
                .andExpect(jsonPath("$.items[2].balanceAfter").value("100.00"));
    }

    @Test
    void counterpartiesAreMaskedAndOwnAccountsAreRecognised() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        String bob = tokenFor("bob@example.com", "Bob", "Miller");
        Acc checking = open(alice, "CHECKING", null);
        Acc savings = open(alice, "SAVINGS", "Holiday");
        Acc bobAcc = open(bob, "CHECKING", null);
        deposit(alice, checking, "200.00", "cash in");
        transfer(alice, checking, bobAcc.iban(), "50.00", "to bob");
        transfer(alice, checking, savings.iban(), "20.00", "to savings");

        MvcResult result = history(alice, checking, "?sort=date,asc").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].counterparty.label").value("Cash deposit"))
                .andExpect(jsonPath("$.items[0].counterparty.iban").doesNotExist())
                .andExpect(jsonPath("$.items[0].counterparty.ownAccount").value(false))
                .andExpect(jsonPath("$.items[1].counterparty.label").value("Bob M."))
                .andExpect(jsonPath("$.items[1].counterparty.iban").value(bobAcc.iban()))
                .andExpect(jsonPath("$.items[1].counterparty.ownAccount").value(false))
                .andExpect(jsonPath("$.items[2].counterparty.label").value("Holiday"))
                .andExpect(jsonPath("$.items[2].counterparty.ownAccount").value(true))
                .andReturn();
        // nothing about the other customer beyond the masked name and IBAN
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("bob@example.com").doesNotContain("Miller").doesNotContain("userId");

        history(bob, bobAcc, "")
                .andExpect(jsonPath("$.items[0].counterparty.label").value("Alice K."))
                .andExpect(jsonPath("$.items[0].counterparty.iban").value(checking.iban()))
                .andExpect(jsonPath("$.items[0].direction").value("IN"));
    }

    // ---- filters ------------------------------------------------------------------------------------------------

    @Test
    void filtersByTypeDirectionAmountAndText() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc aliceAcc = open(alice, "CHECKING", null);
        Acc bobAcc = open(tokenFor("bob@example.com", "Bob", "Miller"), "CHECKING", null);
        deposit(alice, aliceAcc, "500.00", "Salary October");
        deposit(alice, aliceAcc, "20.00", "Gift 100%_special");
        transfer(alice, aliceAcc, bobAcc.iban(), "75.00", "Rent share");
        transfer(alice, aliceAcc, bobAcc.iban(), "5.00", "Coffee");

        history(alice, aliceAcc, "?type=TRANSFER").andExpect(jsonPath("$.totalElements").value(2));
        history(alice, aliceAcc, "?type=DEPOSIT").andExpect(jsonPath("$.totalElements").value(2));
        history(alice, aliceAcc, "?direction=OUT").andExpect(jsonPath("$.totalElements").value(2));
        history(alice, aliceAcc, "?direction=IN").andExpect(jsonPath("$.totalElements").value(2));

        // amount filters use the absolute value, so outgoing money is found by its size too
        history(alice, aliceAcc, "?minAmount=20&maxAmount=75").andExpect(jsonPath("$.totalElements").value(2));
        history(alice, aliceAcc, "?minAmount=75.00&maxAmount=75.00").andExpect(jsonPath("$.items[0].description").value("Rent share"));

        history(alice, aliceAcc, "?q=salary").andExpect(jsonPath("$.totalElements").value(1)); // case-insensitive
        history(alice, aliceAcc, "?q=RENT").andExpect(jsonPath("$.items[0].description").value("Rent share"));
        history(alice, aliceAcc, "?q=nothing-like-this").andExpect(jsonPath("$.totalElements").value(0));

        // '%' and '_' in the search text are literal characters, not wildcards
        historySearch(alice, aliceAcc, "100%_").andExpect(jsonPath("$.totalElements").value(1));
        historySearch(alice, aliceAcc, "%").andExpect(jsonPath("$.totalElements").value(1));

        // combined
        history(alice, aliceAcc, "?type=TRANSFER&direction=OUT&minAmount=10").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void dateRangeIsInclusiveOnBothEndsInUtc() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc acc = open(alice, "CHECKING", null);
        deposit(alice, acc, "1.00", "d1");
        deposit(alice, acc, "2.00", "d2");
        deposit(alice, acc, "3.00", "d3");
        backdate("d1", "2026-03-10T23:59:59Z"); // last second of the 10th
        backdate("d2", "2026-03-11T00:00:00Z"); // first second of the 11th
        backdate("d3", "2026-03-12T12:00:00Z");

        history(alice, acc, "?from=2026-03-11&to=2026-03-11")
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].description").value("d2"));
        history(alice, acc, "?from=2026-03-10&to=2026-03-11").andExpect(jsonPath("$.totalElements").value(2));
        history(alice, acc, "?from=2026-03-12").andExpect(jsonPath("$.totalElements").value(1));
        history(alice, acc, "?to=2026-03-10").andExpect(jsonPath("$.items[0].description").value("d1"));
        history(alice, acc, "?from=2027-01-01").andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---- sorting and paging ------------------------------------------------------------------------------------

    @Test
    void sortsByDateAndAmountInBothDirections() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc acc = open(alice, "CHECKING", null);
        deposit(alice, acc, "10.00", "a");
        deposit(alice, acc, "50.00", "b");
        deposit(alice, acc, "30.00", "c");

        assertThat(all(history(alice, acc, "?sort=date,desc").andReturn().getResponse().getContentAsString(), "description"))
                .containsExactly("c", "b", "a");
        assertThat(all(history(alice, acc, "?sort=date,asc").andReturn().getResponse().getContentAsString(), "description"))
                .containsExactly("a", "b", "c");
        assertThat(all(history(alice, acc, "?sort=amount,desc").andReturn().getResponse().getContentAsString(), "description"))
                .containsExactly("b", "c", "a");
        assertThat(all(history(alice, acc, "?sort=amount,asc").andReturn().getResponse().getContentAsString(), "description"))
                .containsExactly("a", "c", "b");
    }

    @Test
    void pagesThroughManyEntriesWithoutGapsOrDuplicates() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc acc = open(alice, "CHECKING", null);
        for (int i = 0; i < 250; i++) {
            deposit(alice, acc, "1.00", "n" + i);
        }

        Set<String> seen = new HashSet<>();
        for (int page = 0; page < 3; page++) {
            String body = history(alice, acc, "?size=100&page=" + page)
                    .andExpect(jsonPath("$.totalElements").value(250))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.size").value(100))
                    .andReturn().getResponse().getContentAsString();
            List<String> ids = all(body, "transactionId");
            assertThat(ids).hasSize(page < 2 ? 100 : 50);
            seen.addAll(ids);
        }
        assertThat(seen).hasSize(250);

        history(alice, acc, "?size=100&page=3").andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(250));
        history(alice, acc, "?size=100&page=0").andExpect(jsonPath("$.items[0].balanceAfter").value("250.00"));
        history(alice, acc, "?size=100&page=2&sort=date,asc").andExpect(jsonPath("$.items[0].balanceAfter").value("201.00"));
        history(alice, acc, "").andExpect(jsonPath("$.items.length()").value(20)); // default page size
    }

    // ---- access control ---------------------------------------------------------------------------------------

    @Test
    void onlyTheOwnerCanReadAnAccountsHistory() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        String bob = tokenFor("bob@example.com", "Bob", "Miller");
        Acc aliceAcc = open(alice, "CHECKING", null);
        deposit(alice, aliceAcc, "10.00", "private");

        history(bob, aliceAcc, "").andExpect(status().isNotFound());
        export(bob, aliceAcc, "").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/accounts/" + UUID.randomUUID() + "/transactions").header("Authorization", alice))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/accounts/" + aliceAcc.id() + "/transactions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/accounts/" + aliceAcc.id() + "/transactions/export.csv"))
                .andExpect(status().isUnauthorized());
    }

    // ---- validation and injection ------------------------------------------------------------------------------

    @Test
    void invalidParametersAreRejectedWithFieldErrors() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc acc = open(alice, "CHECKING", null);

        for (String query : List.of(
                "?from=2026-13-01", "?from=not-a-date", "?from=2026-05-02&to=2026-05-01", "?minAmount=-1",
                "?minAmount=10&maxAmount=5", "?minAmount=abc", "?size=101", "?size=0", "?page=-1", "?page=abc",
                "?sort=evil", "?sort=date", "?type=BOGUS", "?direction=SIDEWAYS", "?q=" + "x".repeat(101))) {
            history(alice, acc, query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields").isMap());
        }
        export(alice, acc, "?sort=evil").andExpect(status().isBadRequest());
    }

    @Test
    void sqlInjectionAttemptsAreHarmless() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc acc = open(alice, "CHECKING", null);
        deposit(alice, acc, "10.00", "normal");

        // as a search term it is just text that matches nothing
        historySearch(alice, acc, "'; drop table accounts; --")
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        // as a sort it is not on the allow-list
        history(alice, acc, "?sort=" + java.net.URLEncoder.encode("date,desc; drop table accounts", "UTF-8"))
                .andExpect(status().isBadRequest());
        history(alice, acc, "?sort=" + java.net.URLEncoder.encode("(select pg_sleep(5))", "UTF-8"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from accounts", Integer.class)).isGreaterThan(0);
        history(alice, acc, "").andExpect(jsonPath("$.totalElements").value(1));
    }
}
