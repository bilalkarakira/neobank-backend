package com.bilal.neobank.interest;

import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a customer sees. These tests run on the REAL current date (relative to it, never a fixed month), because
 * interest transactions are stamped when the job runs and the history and dashboard views filter by that stamp.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class InterestApiTest extends AnalyticsTestBase {

    @Autowired
    InterestService service;

    @Autowired
    MutableClock mutableClock;

    private YearMonth thisMonth;
    private YearMonth lastMonth;

    @BeforeEach
    void realToday() {
        mutableClock.set(Instant.now());
        thisMonth = YearMonth.now(ZoneOffset.UTC);
        lastMonth = thisMonth.minusMonths(1);
    }

    /** A savings account opened two months ago that held 1,000 for the whole of last month. */
    private Acc earningAccount(String token) throws Exception {
        Acc s = open(token, "SAVINGS");
        jdbc.update("update accounts set created_at = ?::timestamptz where id = ?::uuid",
                thisMonth.minusMonths(2).atDay(1) + "T00:00:00Z", s.id());
        depositOn(s, "1000.00", lastMonth.atDay(1).atTime(0, 5).toInstant(ZoneOffset.UTC));
        return s;
    }

    @Test
    void savingsAccountsShowTheirRateAndOtherAccountsDoNot() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc savings = open(alice, "SAVINGS");
        Acc checking = open(alice, "CHECKING");

        api(alice, "/api/accounts/" + savings.id()).andExpect(jsonPath("$.interestRate").value("3.50"));
        api(alice, "/api/accounts/" + checking.id()).andExpect(jsonPath("$.interestRate").doesNotExist());
        api(alice, "/api/accounts").andExpect(jsonPath("$[?(@.type=='SAVINGS')].interestRate").value("3.50"));
    }

    @Test
    void theInterestEndpointExplainsEachMonthAndTotalsTheYear() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc s = earningAccount(alice);
        service.accrueAllDue();

        // last month: 1000 all month (deposit just after midnight on day 1) x 0.28709% = 2.87
        var result = api(alice, "/api/accounts/" + s.id() + "/interest").andExpect(status().isOk())
                .andExpect(jsonPath("$.apyPercent").value("3.50"))
                .andExpect(jsonPath("$.accruals[0].period").value(lastMonth.toString())) // newest first
                .andExpect(jsonPath("$.accruals[0].status").value("CREDITED"))
                .andExpect(jsonPath("$.accruals[0].averageBalance").value("1000.00"))
                .andExpect(jsonPath("$.accruals[0].amount").value("2.87"))
                .andExpect(jsonPath("$.accruals[1].status").value("SKIPPED_ZERO"));
        // the year total counts months EARNED in the current year (last month may fall in the previous year)
        String expected = lastMonth.getYear() == thisMonth.getYear() ? "2.87" : "0.00";
        result.andExpect(jsonPath("$.earnedThisYear").value(expected));
    }

    @Test
    void checkingAccountsHaveNoInterestAndOnlyTheOwnerCanReadIt() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        Acc savings = open(alice, "SAVINGS");
        Acc checking = open(alice, "CHECKING");

        api(alice, "/api/accounts/" + checking.id() + "/interest").andExpect(status().isOk())
                .andExpect(jsonPath("$.apyPercent").doesNotExist()).andExpect(jsonPath("$.accruals.length()").value(0));
        api(bob, "/api/accounts/" + savings.id() + "/interest").andExpect(status().isNotFound());
        api(bob, "/api/accounts/" + UUID.randomUUID() + "/interest").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/accounts/" + savings.id() + "/interest")).andExpect(status().isUnauthorized());
    }

    @Test
    void interestAppearsInHistoryAsInterestEarnedAndAsIncomeOnTheDashboard() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc s = earningAccount(alice);
        service.accrueAllDue();

        api(alice, "/api/accounts/" + s.id() + "/transactions?type=INTEREST")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].type").value("INTEREST"))
                .andExpect(jsonPath("$.items[0].direction").value("IN"))
                .andExpect(jsonPath("$.items[0].amount").value("2.87"))
                .andExpect(jsonPath("$.items[0].balanceAfter").value("1002.87"))
                .andExpect(jsonPath("$.items[0].description").value(InterestService.description(lastMonth)))
                .andExpect(jsonPath("$.items[0].counterparty.label").value("Interest earned"))
                .andExpect(jsonPath("$.items[0].counterparty.iban").doesNotExist());
        // the deposit is still labelled as cash
        api(alice, "/api/accounts/" + s.id() + "/transactions?type=DEPOSIT")
                .andExpect(jsonPath("$.items[0].counterparty.label").value("Cash deposit"));
        // the CSV carries it too
        String csv = export(alice, s, "?type=INTEREST").andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(csv).contains("INTEREST,IN,2.87,1002.87,Interest earned");

        // received from the bank, so it counts as income in the cash-flow numbers (it was credited just now)
        api(alice, "/api/analytics/cashflow?months=1&accountId=" + s.id())
                .andExpect(jsonPath("$[0].income").value("2.87"));
        assertBooksBalance();
    }

    private org.springframework.test.web.servlet.ResultActions export(String token, Acc account, String query)
            throws Exception {
        return mockMvc.perform(get("/api/accounts/" + account.id() + "/transactions/export.csv" + query)
                .header("Authorization", token));
    }
}
