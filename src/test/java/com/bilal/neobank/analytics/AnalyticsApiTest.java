package com.bilal.neobank.analytics;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AnalyticsApiTest extends AnalyticsTestBase {

    // ---- balance history ---------------------------------------------------------------------------------------

    @Test
    void balanceHistoryCarriesBalancesForwardAndStartsFromTheTrueBalance() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc bob = open(tokenFor("bob@example.com"), "CHECKING");
        depositOn(a, "100.00", daysAgo(10, 9));
        depositOn(a, "50.00", daysAgo(5, 9));
        transferOn(a, bob, "30.00", daysAgo(5, 15), null);

        api(alice, "/api/analytics/balance-history?days=14")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(14))
                .andExpect(jsonPath("$[0].date").value(today().minusDays(13).toString()))
                .andExpect(jsonPath("$[0].balance").value("0.00"))         // before the first entry: nothing yet
                .andExpect(jsonPath("$[3].balance").value("100.00"))       // 10 days ago: the first deposit
                .andExpect(jsonPath("$[7].balance").value("100.00"))       // 6 days ago: carried forward
                .andExpect(jsonPath("$[8].balance").value("120.00"))       // 5 days ago: +50 -30 (end of day)
                .andExpect(jsonPath("$[13].balance").value("120.00"))      // today
                .andExpect(jsonPath("$[13].date").value(today().toString()));

        // a shorter window starts from the balance carried IN from before the window, not from zero
        api(alice, "/api/analytics/balance-history?days=7")
                .andExpect(jsonPath("$[0].balance").value("100.00"));
        assertBooksBalance();
    }

    @Test
    void balanceHistorySumsAllAccountsButCanBeNarrowedToOne() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc checking = open(alice, "CHECKING");
        Acc savings = open(alice, "SAVINGS");
        depositOn(checking, "100.00", daysAgo(3, 9));
        depositOn(savings, "40.00", daysAgo(2, 9));
        transferOn(checking, savings, "25.00", daysAgo(1, 9), null); // moves money, total unchanged

        api(alice, "/api/analytics/balance-history?days=7")
                .andExpect(jsonPath("$[3].balance").value("100.00")) // 3 days ago
                .andExpect(jsonPath("$[4].balance").value("140.00"))
                .andExpect(jsonPath("$[5].balance").value("140.00")) // the own transfer changes nothing in total
                .andExpect(jsonPath("$[6].balance").value("140.00"));
        api(alice, "/api/analytics/balance-history?days=7&accountId=" + checking.id())
                .andExpect(jsonPath("$[5].balance").value("75.00"));
        api(alice, "/api/analytics/balance-history?days=7&accountId=" + savings.id())
                .andExpect(jsonPath("$[3].balance").value("0.00"))
                .andExpect(jsonPath("$[4].balance").value("40.00"))
                .andExpect(jsonPath("$[5].balance").value("65.00"));
    }

    @Test
    void anAccountWithoutActivityIsAFlatZeroAndAUserWithoutAccountsGetsNothing() throws Exception {
        String alice = tokenFor("alice@example.com");
        api(alice, "/api/analytics/balance-history").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        Acc empty = open(alice, "CHECKING");
        api(alice, "/api/analytics/balance-history?days=30&accountId=" + empty.id())
                .andExpect(jsonPath("$.length()").value(30))
                .andExpect(jsonPath("$[0].balance").value("0.00"))
                .andExpect(jsonPath("$[29].balance").value("0.00"));
    }

    // ---- cash flow ---------------------------------------------------------------------------------------------

    private record Fixture(String alice, Acc checking, Acc savings, Acc bob, String bobToken) {
    }

    /** Alice: income and spending in this and last month, plus an own-account move that must count as neither. */
    private Fixture cashflowFixture() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bobToken = tokenFor("bob@example.com");
        Acc checking = open(alice, "CHECKING");
        Acc savings = open(alice, "SAVINGS");
        Acc bob = open(bobToken, "CHECKING");
        LocalDate lastMonthDay = today().withDayOfMonth(1).minusDays(3);

        depositOn(bob, "1000.00", onDay(lastMonthDay, 8));
        depositOn(checking, "500.00", onDay(lastMonthDay, 9));                        // last month income
        transferOn(checking, bob, "80.00", onDay(lastMonthDay, 12), "Dining");         // last month spending

        depositOn(checking, "1000.00", daysAgo(0, 1));                                 // this month income
        transferOn(bob, checking, "200.00", daysAgo(0, 2), "Dining");                  // income (Bob's category is Bob's)
        transferOn(checking, bob, "150.00", daysAgo(0, 3), "Groceries");               // this month spending
        transferOn(checking, savings, "300.00", daysAgo(0, 4), "Housing");             // own move: neither
        return new Fixture(alice, checking, savings, bob, bobToken);
    }

    @Test
    void cashflowCountsDepositsAndIncomingAsIncomeOutgoingAsSpendingAndOwnMovesAsNeither() throws Exception {
        Fixture f = cashflowFixture();

        api(f.alice(), "/api/analytics/cashflow?months=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].income").value("0.00"))       // two months ago: no activity, still listed
                .andExpect(jsonPath("$[0].spending").value("0.00"))
                .andExpect(jsonPath("$[1].income").value("500.00"))
                .andExpect(jsonPath("$[1].spending").value("80.00"))
                .andExpect(jsonPath("$[2].month").value(java.time.YearMonth.from(today()).toString()))
                .andExpect(jsonPath("$[2].income").value("1200.00"))    // 1000 deposit + 200 received
                .andExpect(jsonPath("$[2].spending").value("150.00"));  // the 300 to savings is not spending
        api(f.alice(), "/api/analytics/cashflow?months=1").andExpect(jsonPath("$.length()").value(1));

        // the savings account on its own: the money it received from my checking is not income
        api(f.alice(), "/api/analytics/cashflow?months=1&accountId=" + f.savings().id())
                .andExpect(jsonPath("$[0].income").value("0.00"))
                .andExpect(jsonPath("$[0].spending").value("0.00"));
        assertBooksBalance();
    }

    // ---- spending by category ----------------------------------------------------------------------------------

    @Test
    void spendingByCategoryGroupsOutgoingTransfersToOthersAndSharesAddUpToOneHundred() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc checking = open(alice, "CHECKING");
        Acc savings = open(alice, "SAVINGS");
        Acc bob = open(tokenFor("bob@example.com"), "CHECKING");
        depositOn(checking, "2000.00", daysAgo(20, 8));
        depositOn(bob, "500.00", daysAgo(20, 8));
        transferOn(checking, bob, "60.00", daysAgo(5, 9), "Dining");
        transferOn(checking, bob, "30.00", daysAgo(4, 9), "Groceries");
        transferOn(checking, bob, "20.00", daysAgo(3, 9), "Dining");
        transferOn(checking, bob, "10.00", daysAgo(2, 9), null);              // no category
        transferOn(checking, savings, "500.00", daysAgo(2, 10), "Housing");   // own move: excluded
        transferOn(bob, checking, "100.00", daysAgo(1, 9), "Shopping");       // received money: not my spending

        api(alice, "/api/analytics/spending-by-category")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("120.00"))
                .andExpect(jsonPath("$.categories.length()").value(3))
                .andExpect(jsonPath("$.categories[0].name").value("Dining"))      // largest first
                .andExpect(jsonPath("$.categories[0].total").value("80.00"))
                .andExpect(jsonPath("$.categories[0].share").value(66.7))
                .andExpect(jsonPath("$.categories[1].name").value("Groceries"))
                .andExpect(jsonPath("$.categories[1].share").value(25.0))
                .andExpect(jsonPath("$.categories[2].name").value("Uncategorized"))
                .andExpect(jsonPath("$.categories[2].share").value(8.3));          // 66.7 + 25.0 + 8.3 = 100.0
        assertBooksBalance();
    }

    @Test
    void spendingByCategoryRespectsTheDateRangeAndDefaultsToTheLastThirtyDays() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc checking = open(alice, "CHECKING");
        Acc bob = open(tokenFor("bob@example.com"), "CHECKING");
        depositOn(checking, "1000.00", daysAgo(60, 8));
        transferOn(checking, bob, "40.00", daysAgo(40, 9), "Travel");
        transferOn(checking, bob, "15.00", daysAgo(10, 9), "Transport");

        api(alice, "/api/analytics/spending-by-category")
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].name").value("Transport"))
                .andExpect(jsonPath("$.from").value(today().minusDays(29).toString()))
                .andExpect(jsonPath("$.to").value(today().toString()));
        api(alice, "/api/analytics/spending-by-category?from=" + today().minusDays(45))
                .andExpect(jsonPath("$.categories.length()").value(2));
        // the range is inclusive at both ends, in UTC
        api(alice, "/api/analytics/spending-by-category?from=" + today().minusDays(40) + "&to=" + today().minusDays(40))
                .andExpect(jsonPath("$.categories[0].name").value("Travel"))
                .andExpect(jsonPath("$.total").value("40.00"));
        api(alice, "/api/analytics/spending-by-category?from=" + today().minusDays(39) + "&to=" + today().minusDays(11))
                .andExpect(jsonPath("$.categories.length()").value(0))
                .andExpect(jsonPath("$.total").value("0.00"));
    }

    // ---- summary -----------------------------------------------------------------------------------------------

    @Test
    void summaryComparesThisMonthWithLastMonth() throws Exception {
        Fixture f = cashflowFixture();

        api(f.alice(), "/api/analytics/summary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.totalBalance").value("1470.00"))   // checking 1170 + savings 300
                .andExpect(jsonPath("$.thisMonth.income").value("1200.00"))
                .andExpect(jsonPath("$.thisMonth.spending").value("150.00"))
                .andExpect(jsonPath("$.thisMonth.net").value("1050.00"))
                .andExpect(jsonPath("$.lastMonth.income").value("500.00"))
                .andExpect(jsonPath("$.lastMonth.spending").value("80.00"))
                .andExpect(jsonPath("$.lastMonth.net").value("420.00"))
                .andExpect(jsonPath("$.changePercent.income").value(140.0))
                .andExpect(jsonPath("$.changePercent.spending").value(87.5))
                .andExpect(jsonPath("$.changePercent.net").value(150.0));
    }

    @Test
    void summaryHasNoPercentageWhenThereIsNothingToCompareWith() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc checking = open(alice, "CHECKING");
        depositOn(checking, "100.00", daysAgo(0, 1));

        api(alice, "/api/analytics/summary")
                .andExpect(jsonPath("$.thisMonth.income").value("100.00"))
                .andExpect(jsonPath("$.lastMonth.income").value("0.00"))
                .andExpect(jsonPath("$.changePercent.income").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.changePercent.net").value(org.hamcrest.Matchers.nullValue()));

        // a brand-new user with no accounts gets a valid, all-zero summary
        api(tokenFor("new@example.com"), "/api/analytics/summary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBalance").value("0.00"))
                .andExpect(jsonPath("$.thisMonth.net").value("0.00"));
    }

    // ---- access control and validation -------------------------------------------------------------------------

    @Test
    void otherUsersAccountsAreNotFoundAndTokensAreRequired() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc aliceAcc = open(alice, "CHECKING");
        String bob = tokenFor("bob@example.com");

        for (String endpoint : List.of("summary", "balance-history", "cashflow", "spending-by-category")) {
            api(bob, "/api/analytics/" + endpoint + "?accountId=" + aliceAcc.id()).andExpect(status().isNotFound());
            api(bob, "/api/analytics/" + endpoint + "?accountId=" + UUID.randomUUID()).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/analytics/" + endpoint)).andExpect(status().isUnauthorized());
        }
        // without an accountId a user only ever sees their own data
        api(bob, "/api/analytics/balance-history").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void badParametersAreRejected() throws Exception {
        String alice = tokenFor("alice@example.com");
        open(alice, "CHECKING");

        for (String query : List.of(
                "balance-history?days=6", "balance-history?days=366", "balance-history?days=abc",
                "cashflow?months=0", "cashflow?months=13", "cashflow?months=x",
                "spending-by-category?from=2026-05-02&to=2026-05-01", "spending-by-category?from=nope",
                "spending-by-category?from=2024-01-01&to=2026-01-01", "summary?accountId=not-a-uuid")) {
            api(alice, "/api/analytics/" + query).andExpect(status().isBadRequest());
        }
    }
}
