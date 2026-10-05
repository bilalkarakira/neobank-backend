package com.bilal.neobank.history;

import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.recurring.RecurringExecutor;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payments made by a standing order are marked in the history, on both sides, and nothing else is. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HistoryRecurringMarkerTest extends HistoryTestBase {

    @Autowired
    RecurringExecutor executor;

    private void standingOrder(String token, Acc source, Acc destination, String amount, String description) throws Exception {
        mockMvc.perform(post("/api/recurring-payments").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceAccountId":"%s","destinationIban":"%s","amount":%s,"description":"%s",
                         "frequency":"MONTHLY","startDate":"%s"}
                        """.formatted(source.id(), destination.iban(), amount, description,
                        LocalDate.now(ZoneOffset.UTC)))).andExpect(status().isCreated());
    }

    private List<Boolean> flagsFor(String json, String description) {
        return JsonPath.read(json, "$.items[?(@.description == '" + description + "')].recurring");
    }

    @Test
    void aPaymentMadeByAStandingOrderIsMarkedForBothSidesAndOrdinaryOnesAreNot() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        String bob = tokenFor("bob@example.com", "Bob", "Miller");
        Acc a = open(alice, "CHECKING", null);
        Acc b = open(bob, "CHECKING", null);
        deposit(alice, a, "500.00", "Top-up");
        transfer(alice, a, b.iban(), "10.00", "Lunch");          // a normal transfer
        standingOrder(alice, a, b, "25.00", "Gym");

        assertThat(executor.runAllDue().succeeded()).isEqualTo(1);

        String sender = history(alice, a, "").andReturn().getResponse().getContentAsString();
        String receiver = history(bob, b, "").andReturn().getResponse().getContentAsString();
        assertThat(flagsFor(sender, "Gym")).containsExactly(true);
        assertThat(flagsFor(receiver, "Gym")).containsExactly(true);
        assertThat(flagsFor(sender, "Lunch")).containsExactly(false);
        assertThat(flagsFor(receiver, "Lunch")).containsExactly(false);
        assertThat(flagsFor(sender, "Top-up")).containsExactly(false);
        assertThat((Integer) JsonPath.read(sender, "$.totalElements")).isEqualTo(3); // the marker never duplicates or hides a row
    }

    @Test
    void aFailedRunLeavesNoTransactionSoNothingIsMarked() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc a = open(alice, "CHECKING", null);
        Acc b = open(tokenFor("bob@example.com", "Bob", "Miller"), "CHECKING", null);
        deposit(alice, a, "5.00", "Top-up");
        standingOrder(alice, a, b, "25.00", "Gym"); // more than the balance

        assertThat(executor.runAllDue().failed()).isEqualTo(1);

        String sender = history(alice, a, "").andReturn().getResponse().getContentAsString();
        assertThat(flagsFor(sender, "Gym")).isEmpty();
        assertThat(flagsFor(sender, "Top-up")).containsExactly(false);
    }

    @Test
    void theMarkerFollowsItsRowThroughFiltersAndSorting() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc a = open(alice, "CHECKING", null);
        Acc b = open(tokenFor("bob@example.com", "Bob", "Miller"), "CHECKING", null);
        deposit(alice, a, "500.00", "Top-up");
        transfer(alice, a, b.iban(), "10.00", "Lunch");
        standingOrder(alice, a, b, "25.00", "Gym");
        executor.runAllDue();

        String onlyTransfers = history(alice, a, "?type=TRANSFER").andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(onlyTransfers, "$.totalElements")).isEqualTo(2);
        assertThat(flagsFor(onlyTransfers, "Gym")).containsExactly(true);
        assertThat(flagsFor(onlyTransfers, "Lunch")).containsExactly(false);

        String searched = historySearch(alice, a, "Gym").andReturn().getResponse().getContentAsString();
        assertThat(flagsFor(searched, "Gym")).containsExactly(true);
    }

    @Test
    void theReceiverLearnsThatItWasRecurringButNothingAboutTheOrderItself() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        String bob = tokenFor("bob@example.com", "Bob", "Miller");
        Acc a = open(alice, "CHECKING", null);
        Acc b = open(bob, "CHECKING", null);
        deposit(alice, a, "500.00", "Top-up");
        standingOrder(alice, a, b, "25.00", "Gym");
        executor.runAllDue();
        String orderId = jdbc.queryForObject("select id::text from recurring_payments", String.class);

        String receiver = history(bob, b, "").andReturn().getResponse().getContentAsString();

        assertThat(receiver).doesNotContain(orderId).doesNotContain("paymentId").doesNotContain("frequency");
    }

    // ---- the "recurring" filter ---------------------------------------------------------------------------------

    /** Alice: a deposit, an ordinary transfer and one standing-order payment. Returns her token and account. */
    private record Scenario(String token, Acc account, Acc other, String otherToken) {
    }

    private Scenario threeKindsOfRows() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc a = open(alice, "CHECKING", null);
        String bob = tokenFor("bob@example.com", "Bob", "Miller");
        Acc b = open(bob, "CHECKING", null);
        deposit(alice, a, "500.00", "Top-up");
        transfer(alice, a, b.iban(), "10.00", "Lunch");
        standingOrder(alice, a, b, "25.00", "Gym");
        executor.runAllDue();
        return new Scenario(alice, a, b, bob);
    }

    private List<String> descriptions(Scenario s, String query) throws Exception {
        String json = history(s.token(), s.account(), query).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.items[*].description");
    }

    @Test
    void recurringTrueShowsOnlyPaymentsMadeByAStandingOrder() throws Exception {
        Scenario s = threeKindsOfRows();

        assertThat(descriptions(s, "?recurring=true")).containsExactly("Gym");
        String json = history(s.token(), s.account(), "?recurring=true").andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(json, "$.totalElements")).isEqualTo(1); // the count follows the filter too
    }

    @Test
    void recurringFalseShowsEverythingElse() throws Exception {
        Scenario s = threeKindsOfRows();

        assertThat(descriptions(s, "?recurring=false")).containsExactlyInAnyOrder("Lunch", "Top-up");
    }

    @Test
    void leavingTheFilterOutShowsAllThreeAsBefore() throws Exception {
        Scenario s = threeKindsOfRows();

        assertThat(descriptions(s, "")).containsExactlyInAnyOrder("Gym", "Lunch", "Top-up");
    }

    @Test
    void theFilterCombinesWithTheOtherFiltersAndWithPaging() throws Exception {
        Scenario s = threeKindsOfRows();

        assertThat(descriptions(s, "?recurring=false&type=TRANSFER")).containsExactly("Lunch");
        assertThat(descriptions(s, "?recurring=false&direction=IN")).containsExactly("Top-up");
        assertThat(descriptions(s, "?recurring=true&direction=IN")).isEmpty(); // Alice only SENT the recurring payment
        assertThat(descriptions(s, "?recurring=false&q=lunch")).containsExactly("Lunch");
        assertThat(descriptions(s, "?recurring=true&q=lunch")).isEmpty();      // a search never widens the filter

        String paged = history(s.token(), s.account(), "?recurring=false&size=1&page=1").andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(paged, "$.totalElements")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(paged, "$.totalPages")).isEqualTo(2);
        assertThat((List<?>) JsonPath.read(paged, "$.items")).hasSize(1);
    }

    @Test
    void theReceiverCanFilterTheSameWayOnTheirSide() throws Exception {
        Scenario s = threeKindsOfRows();
        String json = history(s.otherToken(), s.other(), "?recurring=true").andReturn().getResponse().getContentAsString();

        assertThat((List<?>) JsonPath.read(json, "$.items")).hasSize(1);
    }

    @Test
    void anInvalidValueIsAValidationErrorNotAnIgnoredFilter() throws Exception {
        Scenario s = threeKindsOfRows();

        history(s.token(), s.account(), "?recurring=maybe").andExpect(status().isBadRequest());
        history(s.token(), s.account(), "?recurring=2").andExpect(status().isBadRequest());
        history(s.token(), s.account(), "?recurring=nope").andExpect(status().isBadRequest());
    }

    @Test
    void theCsvExportFollowsTheSameFilter() throws Exception {
        Scenario s = threeKindsOfRows();

        String onlyRecurring = export(s.token(), s.account(), "?recurring=true").andReturn().getResponse().getContentAsString();
        String notRecurring = export(s.token(), s.account(), "?recurring=false").andReturn().getResponse().getContentAsString();

        assertThat(onlyRecurring).contains("Gym").doesNotContain("Lunch").doesNotContain("Top-up");
        assertThat(notRecurring).contains("Lunch").contains("Top-up").doesNotContain("Gym");
    }

    @Test
    void theCsvExportIsUnchangedBecauseNewColumnsCouldBreakWhoeverParsesIt() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc a = open(alice, "CHECKING", null);
        Acc b = open(tokenFor("bob@example.com", "Bob", "Miller"), "CHECKING", null);
        deposit(alice, a, "500.00", "Top-up");
        standingOrder(alice, a, b, "25.00", "Gym");
        executor.runAllDue();

        String csv = export(alice, a, "").andReturn().getResponse().getContentAsString();

        assertThat(csv.toLowerCase()).doesNotContain("recurring");
        assertThat(csv).contains("Gym");
    }
}
