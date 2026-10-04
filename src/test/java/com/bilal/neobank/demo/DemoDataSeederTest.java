package com.bilal.neobank.demo;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.ledger.LedgerReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.demo.seed-on-startup=false")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration.class)
class DemoDataSeederTest {

    @Autowired
    DemoDataSeeder seeder;

    @Autowired
    DatabaseCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LedgerReconciliationService reconciliation;

    @BeforeEach
    void clean() {
        cleaner.clean();
    }

    private String demoToken() throws Exception {
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(DemoDataSeeder.DEMO_EMAIL, DemoDataSeeder.DEMO_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void createsAHealthyLedgerAndALoginThatWorks() throws Exception {
        assertThat(seeder.seed()).isTrue();

        var report = reconciliation.check();
        assertThat(report.isHealthy()).as(report.toString()).isTrue();
        assertThat(count("select count(*) from transactions")).isGreaterThan(100);
        assertThat(count("select count(*) from ledger_entries")).isEqualTo(2 * count("select count(*) from transactions"));
        assertThat(demoToken()).startsWith("Bearer ");
    }

    @Test
    void theSavingsAccountEarnsMonthlyInterestThatCompounds() throws Exception {
        seeder.seed();

        var rows = jdbc.queryForList("""
                select a.period, a.amount, a.average_balance, t.type, t.initiated_by
                from interest_accruals a join transactions t on t.id = a.transaction_id
                join accounts acc on acc.id = a.account_id
                where acc.nickname = 'Rainy day' and a.status = 'CREDITED' order by a.period
                """);
        assertThat(rows.size()).isGreaterThanOrEqualTo(4); // one per completed month of the demo history
        for (var row : rows) {
            assertThat(row.get("type")).isEqualTo("INTEREST");
            assertThat(row.get("initiated_by")).isNull();
            assertThat((BigDecimal) row.get("amount")).isGreaterThan(BigDecimal.ZERO);
        }
        // the savings balance keeps growing, so each month's average is higher than the last
        for (int i = 1; i < rows.size(); i++) {
            assertThat((BigDecimal) rows.get(i).get("average_balance"))
                    .isGreaterThan((BigDecimal) rows.get(i - 1).get("average_balance"));
        }
        // it shows up in the customer's history with the right label, and the books still balance
        String history = mockMvc.perform(get("/api/accounts/" + jdbc.queryForObject(
                "select id::text from accounts where nickname = 'Rainy day'", String.class)
                + "/transactions?type=INTEREST").header("Authorization", demoToken())).andReturn().getResponse()
                .getContentAsString();
        assertThat(history).contains("\"label\":\"Interest earned\"");
        assertThat(reconciliation.check().isHealthy()).isTrue();
    }

    @Test
    void isIdempotent() {
        assertThat(seeder.seed()).isTrue();
        int users = count("select count(*) from users");
        int transactions = count("select count(*) from transactions");

        assertThat(seeder.seed()).isFalse();

        assertThat(count("select count(*) from users")).isEqualTo(users);
        assertThat(count("select count(*) from transactions")).isEqualTo(transactions);
    }

    @Test
    void producesDataThatMakesEveryDashboardViewInteresting() throws Exception {
        seeder.seed();
        String token = demoToken();

        // six months of cash flow, each with a salary
        String cashflow = mockMvc.perform(get("/api/analytics/cashflow?months=6").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6))
                .andReturn().getResponse().getContentAsString();
        for (String income : values(cashflow, "income")) {
            assertThat(new BigDecimal(income)).isGreaterThanOrEqualTo(new BigDecimal("3200.00"));
        }
        for (String spending : values(cashflow, "spending")) {
            assertThat(new BigDecimal(spending)).isGreaterThan(new BigDecimal("1000.00"));
        }

        // several categories, shares adding up to exactly 100
        String categories = mockMvc.perform(get("/api/analytics/spending-by-category?from="
                + java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(180)).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> shares = values(categories, "share");
        assertThat(shares.size()).isGreaterThanOrEqualTo(6);
        assertThat(shares.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100.0");

        // the balance chart ends exactly at the real total balance
        String history = mockMvc.perform(get("/api/analytics/balance-history?days=180").header("Authorization", token))
                .andExpect(jsonPath("$.length()").value(180)).andReturn().getResponse().getContentAsString();
        List<String> balances = values(history, "balance");
        String summary = mockMvc.perform(get("/api/analytics/summary").header("Authorization", token))
                .andReturn().getResponse().getContentAsString();
        assertThat(balances.get(balances.size() - 1)).isEqualTo(summary.replaceAll(".*\"totalBalance\":\"([^\"]+)\".*", "$1"));

        // a window that lies fully inside the generated history starts from a real, positive balance
        String recent = mockMvc.perform(get("/api/analytics/balance-history?days=60").header("Authorization", token))
                .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(values(recent, "balance").get(0))).isGreaterThan(BigDecimal.ZERO);
    }

    private static List<String> values(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"?([0-9.\\-]+)\"?").matcher(json);
        List<String> out = new java.util.ArrayList<>();
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }
}
