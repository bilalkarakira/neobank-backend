package com.bilal.neobank.history;

import com.bilal.neobank.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Keyset paging: "continue after this row" instead of "skip N rows". */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HistoryCursorTest extends HistoryTestBase {

    private String alice;
    private Acc account;

    /** Eleven entries with deliberate ties: three share one instant, and several share an amount. */
    private void seed() throws Exception {
        alice = tokenFor("alice@example.com", "Alice", "Kent");
        account = open(alice, "CHECKING", null);
        String[] amounts = {"10.00", "20.00", "20.00", "20.00", "5.00", "7.50", "10.00", "99.00", "20.00", "1.00", "10.00"};
        for (int i = 0; i < amounts.length; i++) {
            deposit(alice, account, amounts[i], "entry-" + i);
        }
        backdate("entry-0", "2026-01-01T10:00:00Z");
        backdate("entry-1", "2026-01-02T10:00:00Z");
        backdate("entry-2", "2026-01-02T10:00:00Z"); // the same instant as entry-1 and entry-3
        backdate("entry-3", "2026-01-02T10:00:00Z");
        backdate("entry-4", "2026-01-03T10:00:00Z");
    }

    private List<String> walkByCursor(String sort, String extra, int size) throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 50; guard++) {
            String query = "?size=" + size + "&sort=" + sort + extra + (cursor == null ? "" : "&cursor=" + cursor);
            String body = history(alice, account, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.items[*].description"));
            cursor = JsonPath.read(body, "$.nextCursor");
            if (cursor == null) {
                return seen;
            }
        }
        throw new AssertionError("the cursor never ended");
    }

    private List<String> wholeHistory(String sort, String extra) throws Exception {
        String body = history(alice, account, "?size=100&sort=" + sort + extra).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].description");
    }

    @Test
    void walkingByCursorVisitsEveryRowOnceInTheSameOrderAsOneBigPageForEverySort() throws Exception {
        seed();
        for (String sort : List.of("date,desc", "date,asc", "amount,desc", "amount,asc")) {
            List<String> walked = walkByCursor(sort, "", 3);
            assertThat(walked).as(sort).hasSize(11).doesNotHaveDuplicates().isEqualTo(wholeHistory(sort, ""));
        }
    }

    @Test
    void ageTiesAndAmountTiesNeverSkipOrRepeatARowAtAPageBoundary() throws Exception {
        seed();
        // page sizes 1 and 2 put a boundary inside every tie group
        for (int size : List.of(1, 2, 4)) {
            for (String sort : List.of("date,desc", "date,asc", "amount,desc", "amount,asc")) {
                assertThat(walkByCursor(sort, "", size)).as(sort + " size " + size)
                        .containsExactlyInAnyOrderElementsOf(wholeHistory(sort, "")).doesNotHaveDuplicates();
            }
        }
    }

    @Test
    void aNewRowArrivingBetweenPagesDoesNotShiftTheNextPage() throws Exception {
        seed();
        String first = history(alice, account, "?size=3").andReturn().getResponse().getContentAsString();
        List<String> firstPage = JsonPath.read(first, "$.items[*].description");
        String cursor = JsonPath.read(first, "$.nextCursor");

        deposit(alice, account, "3.00", "arrived-meanwhile"); // newest entry: lands at the top

        String byCursor = history(alice, account, "?size=3&cursor=" + cursor).andReturn().getResponse().getContentAsString();
        List<String> next = JsonPath.read(byCursor, "$.items[*].description");
        assertThat(next).doesNotContainAnyElementsOf(firstPage).doesNotContain("arrived-meanwhile");

        // counting rows would have repeated the last row of page one at the top of page two
        String byOffset = history(alice, account, "?size=3&page=1").andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(byOffset, "$.items[*].description")).contains(firstPage.get(2));
    }

    @Test
    void filtersStillApplyAlongsideTheCursor() throws Exception {
        seed();
        Acc bob = open(tokenFor("bob@example.com", "Bob", "Miller"), "CHECKING", null);
        transfer(alice, account, bob.iban(), "4.00", "out-1");
        transfer(alice, account, bob.iban(), "6.00", "out-2");
        transfer(alice, account, bob.iban(), "8.00", "out-3");

        List<String> outgoing = walkByCursor("date,desc", "&direction=OUT", 2);
        assertThat(outgoing).containsExactly("out-3", "out-2", "out-1");
        List<String> big = walkByCursor("amount,desc", "&minAmount=10&maxAmount=20", 2);
        assertThat(big).hasSameSizeAs(wholeHistory("amount,desc", "&minAmount=10&maxAmount=20")).isNotEmpty();
    }

    @Test
    void theNextCursorIsOnlyGivenWhenThereIsAnotherRowAndTheTotalsStayWhole() throws Exception {
        seed();
        history(alice, account, "?size=11").andExpect(jsonPath("$.nextCursor").doesNotExist());
        history(alice, account, "?size=10").andExpect(jsonPath("$.nextCursor").isString());
        history(alice, account, "?size=100").andExpect(jsonPath("$.nextCursor").doesNotExist());
        history(alice, account, "?size=5&page=2").andExpect(jsonPath("$.nextCursor").doesNotExist()); // the last page

        String first = history(alice, account, "?size=4").andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        history(alice, account, "?size=4&cursor=" + cursor)
                .andExpect(jsonPath("$.totalElements").value(11)) // the whole filtered history, not what is left
                .andExpect(jsonPath("$.items.length()").value(4));
        history(alice, account, "?size=4&direction=OUT").andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void aCursorThatIsNotOneOfOursOrDoesNotFitTheRequestIsRefused() throws Exception {
        seed();
        String first = history(alice, account, "?size=3&sort=amount,desc").andReturn().getResponse().getContentAsString();
        String amountCursor = JsonPath.read(first, "$.nextCursor");

        for (String bad : List.of("garbage", "!!!", "YQ", "", "Zm9vfGJhcnw5")) {
            if (!bad.isEmpty()) {
                history(alice, account, "?cursor=" + bad).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.fields.cursor").exists());
            }
        }
        // made for another sort order
        history(alice, account, "?sort=date,desc&cursor=" + amountCursor).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.cursor").value("belongs to a different sort order"));
        // two ways of saying where to start
        history(alice, account, "?sort=amount,desc&page=1&cursor=" + amountCursor).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.cursor").value("cannot be combined with page"));
        // the CSV export has no paging, so it ignores a cursor
        export(alice, account, "?cursor=" + amountCursor).andExpect(status().isOk());
    }

    @Test
    void aCursorFromAnotherCustomersRowOnlyEverReturnsTheCallersOwnRows() throws Exception {
        seed();
        String bob = tokenFor("bob@example.com", "Bob", "Miller");
        Acc bobAccount = open(bob, "CHECKING", null);
        deposit(bob, bobAccount, "42.00", "bobs-secret");
        String bobsPage = history(bob, bobAccount, "?size=1").andReturn().getResponse().getContentAsString();
        // bob's only row has no next page; use alice's cursor on bob's account and bob's data stays bob's
        String alicesCursor = JsonPath.read(history(alice, account, "?size=3").andReturn().getResponse().getContentAsString(), "$.nextCursor");

        String onBob = history(bob, bobAccount, "?cursor=" + alicesCursor).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(onBob).doesNotContain("entry-");
        // and alice cannot read bob's account with any cursor
        history(alice, bobAccount, "?cursor=" + alicesCursor).andExpect(status().isNotFound());
        assertThat(bobsPage).contains("bobs-secret");
    }
}
