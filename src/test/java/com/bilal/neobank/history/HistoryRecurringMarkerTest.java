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
