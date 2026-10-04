package com.bilal.neobank.admin;

import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminApiTest extends AnalyticsTestBase {

    @MockitoSpyBean
    AdminRepository adminRepository;

    private String adminToken() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Ada","lastName":"Admin","email":"ada@example.com","password":"%s"}
                """.formatted(PASSWORD))).andExpect(status().isCreated());
        jdbc.update("update users set role = 'ADMIN' where email = 'ada@example.com'");
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"ada@example.com","password":"%s"}
                """.formatted(PASSWORD))).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) {
            request.header("Authorization", token);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private static final String REASON = "{\"reason\":\"Suspicious activity\"}";

    private String statusOf(Acc account) {
        return jdbc.queryForObject("select status from accounts where id = ?::uuid", String.class, account.id());
    }

    private int auditRows() {
        return jdbc.queryForObject("select count(*) from admin_actions", Integer.class);
    }

    // ---- who may enter ----------------------------------------------------------------------------------------

    @Test
    void everyAdminEndpointRejectsAnonymousCallersAndCustomers() throws Exception {
        String customer = tokenFor("alice@example.com");
        Acc a = open(customer, "CHECKING");
        String id = a.id();
        String[][] endpoints = {
                { "GET", "/api/admin/overview" }, { "GET", "/api/admin/reconciliation" },
                { "GET", "/api/admin/accounts" }, { "GET", "/api/admin/audit" },
                { "POST", "/api/admin/accounts/" + id + "/freeze" }, { "POST", "/api/admin/accounts/" + id + "/unfreeze" },
                { "POST", "/api/admin/jobs/interest" }, { "POST", "/api/admin/jobs/recurring" } };

        for (String[] endpoint : endpoints) {
            MockHttpServletRequestBuilder anonymous = endpoint[0].equals("GET") ? get(endpoint[1]) : post(endpoint[1]);
            send(anonymous, null, REASON).andExpect(status().isUnauthorized());
            MockHttpServletRequestBuilder asCustomer = endpoint[0].equals("GET") ? get(endpoint[1]) : post(endpoint[1]);
            send(asCustomer, customer, REASON).andExpect(status().isForbidden());
        }
        assertThat(statusOf(a)).isEqualTo("ACTIVE"); // and nothing changed
        assertThat(auditRows()).isZero();
    }

    @Test
    void anAdminTokenOpensTheAdminEndpoints() throws Exception {
        String admin = adminToken();
        send(get("/api/admin/overview"), admin, null).andExpect(status().isOk());
        send(get("/api/admin/audit"), admin, null).andExpect(status().isOk());
    }

    // ---- overview ---------------------------------------------------------------------------------------------

    @Test
    void overviewCountsOnlyCustomersAndWorksOutVolumesByTimeWindow() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "SAVINGS");
        Acc c = open(tokenFor("carol@example.com"), "CHECKING");
        depositOn(a, "1000.00", Instant.now().minus(60, ChronoUnit.DAYS));
        transferOn(a, b, "100.00", Instant.now().minus(2, ChronoUnit.HOURS), null);   // inside 24h and 30d
        transferOn(a, b, "50.00", Instant.now().minus(10, ChronoUnit.DAYS), null);    // inside 30d only
        transferOn(a, b, "25.00", Instant.now().minus(40, ChronoUnit.DAYS), null);    // outside both
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", c.id());

        send(get("/api/admin/overview"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.customers").value(3)) // the admin is not a customer
                .andExpect(jsonPath("$.accounts.active").value(2))
                .andExpect(jsonPath("$.accounts.frozen").value(1))
                .andExpect(jsonPath("$.accounts.closed").value(0))
                .andExpect(jsonPath("$.depositsHeld").value("1000.00")) // 825.00 + 175.00; the bank's own accounts excluded
                .andExpect(jsonPath("$.transfersLast24Hours.count").value(1))
                .andExpect(jsonPath("$.transfersLast24Hours.amount").value("100.00"))
                .andExpect(jsonPath("$.transfersLast30Days.count").value(2))
                .andExpect(jsonPath("$.transfersLast30Days.amount").value("150.00"))
                .andExpect(jsonPath("$.interestPaid").value("0.00"))
                .andExpect(jsonPath("$.recurring.active").value(0))
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    // ---- reconciliation ---------------------------------------------------------------------------------------

    @Test
    void reconciliationIsHealthyOnCleanBooksAndNamesAnAccountWhoseBalanceDrifted() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");
        depositOn(a, "100.00", Instant.now().minus(1, ChronoUnit.DAYS));

        send(get("/api/admin/reconciliation"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.totalOfAllEntries").value("0.00"))
                .andExpect(jsonPath("$.accountsWithWrongBalance.length()").value(0));

        jdbc.update("update accounts set balance = balance + 1 where id = ?::uuid", a.id()); // simulated corruption

        send(get("/api/admin/reconciliation"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(false))
                .andExpect(jsonPath("$.accountsWithWrongBalance[0]").value(a.id()));
    }

    // ---- searching accounts -----------------------------------------------------------------------------------

    @Test
    void searchFindsByEmailOrIbanFiltersByStatusAndNeverShowsBankAccounts() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice.smith@example.com"), "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "SAVINGS");
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", b.id());

        send(get("/api/admin/accounts"), admin, null).andExpect(jsonPath("$.total").value(2));
        send(get("/api/admin/accounts?q=SMITH"), admin, null).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].ownerEmail").value("alice.smith@example.com"))
                .andExpect(jsonPath("$.items[0].iban").value(a.iban()))
                .andExpect(jsonPath("$.items[0].ownerName").value("Test User"));
        String spaced = b.iban().replaceAll("(.{4})", "$1 ").trim(); // as customers write it: in groups of four
        send(get("/api/admin/accounts").param("q", spaced), admin, null).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(b.id()));
        send(get("/api/admin/accounts?status=FROZEN"), admin, null).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("FROZEN"));
        send(get("/api/admin/accounts?q=SYSTEM"), admin, null).andExpect(jsonPath("$.total").value(0));
        send(get("/api/admin/accounts").param("q", "%"), admin, null).andExpect(jsonPath("$.total").value(0)); // "%" is text, not a wildcard
        send(get("/api/admin/accounts").param("q", "_"), admin, null).andExpect(jsonPath("$.total").value(0));
        send(get("/api/admin/accounts?status=NOPE"), admin, null).andExpect(status().isBadRequest());
    }

    @Test
    void resultsArePagedAndThePageSizeIsCapped() throws Exception {
        String admin = adminToken();
        for (int i = 0; i < 3; i++) {
            open(tokenFor("user" + i + "@example.com"), "CHECKING");
        }

        send(get("/api/admin/accounts?size=2&page=0"), admin, null).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(3));
        send(get("/api/admin/accounts?size=2&page=1"), admin, null).andExpect(jsonPath("$.items.length()").value(1));
        send(get("/api/admin/accounts?size=100000"), admin, null).andExpect(jsonPath("$.size").value(50));
        send(get("/api/admin/accounts?page=-3&size=0"), admin, null).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1));
    }

    // ---- freeze / unfreeze ------------------------------------------------------------------------------------

    @Test
    void aFrozenAccountCannotSendOrReceiveUntilItIsUnfrozen() throws Exception {
        String admin = adminToken();
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        depositOn(a, "500.00", Instant.now().minus(1, ChronoUnit.DAYS));
        String transfer = "{\"sourceAccountId\":\"%s\",\"destinationIban\":\"%s\",\"amount\":10.00}"
                .formatted(a.id(), b.iban());

        send(post("/api/admin/accounts/" + a.id() + "/freeze"), admin, REASON).andExpect(status().isNoContent());

        assertThat(statusOf(a)).isEqualTo("FROZEN");
        send(post("/api/transfers").header("Idempotency-Key", "frozen-test-001"), alice, transfer)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        send(post("/api/accounts/" + a.id() + "/deposits").header("Idempotency-Key", "frozen-test-002"), alice,
                "{\"amount\":5.00}").andExpect(status().isUnprocessableEntity());

        send(post("/api/admin/accounts/" + a.id() + "/unfreeze"), admin, "{\"reason\":\"Cleared after review\"}")
                .andExpect(status().isNoContent());

        assertThat(statusOf(a)).isEqualTo("ACTIVE");
        send(post("/api/transfers").header("Idempotency-Key", "frozen-test-003"), alice, transfer)
                .andExpect(status().isCreated());
        assertBooksBalance();
    }

    @Test
    void everyStatusChangeIsAuditedWithWhoWhichAccountAndWhy() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");

        send(post("/api/admin/accounts/" + a.id() + "/freeze"), admin, "{\"reason\":\"  Fraud report #42 \"}")
                .andExpect(status().isNoContent());
        send(post("/api/admin/accounts/" + a.id() + "/unfreeze"), admin, "{\"reason\":\"False alarm\"}")
                .andExpect(status().isNoContent());

        send(get("/api/admin/audit"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("UNFREEZE_ACCOUNT")) // newest first
                .andExpect(jsonPath("$[0].reason").value("False alarm"))
                .andExpect(jsonPath("$[1].action").value("FREEZE_ACCOUNT"))
                .andExpect(jsonPath("$[1].reason").value("Fraud report #42"))
                .andExpect(jsonPath("$[1].adminEmail").value("ada@example.com"))
                .andExpect(jsonPath("$[1].accountIban").value(a.iban()));
    }

    @Test
    void invalidStatusChangesAreRefusedAndLeaveNoAuditTrace() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");
        Acc closed = open(tokenFor("bob@example.com"), "CHECKING");
        jdbc.update("update accounts set status = 'CLOSED' where id = ?::uuid", closed.id());

        send(post("/api/admin/accounts/" + a.id() + "/unfreeze"), admin, REASON).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADMIN_INVALID_STATE")); // not frozen
        send(post("/api/admin/accounts/" + closed.id() + "/freeze"), admin, REASON).andExpect(status().isUnprocessableEntity());
        send(post("/api/admin/accounts/" + a.id() + "/freeze"), admin, REASON).andExpect(status().isNoContent());
        send(post("/api/admin/accounts/" + a.id() + "/freeze"), admin, REASON).andExpect(status().isUnprocessableEntity()); // twice
        send(post("/api/admin/accounts/" + UUID.randomUUID() + "/freeze"), admin, REASON).andExpect(status().isNotFound());
        send(post("/api/admin/accounts/00000000-0000-0000-0000-000000000001/freeze"), admin, REASON)
                .andExpect(status().isNotFound()); // the bank's funding account is invisible here
        send(post("/api/admin/accounts/" + a.id() + "/unfreeze"), admin, "{\"reason\":\"  \"}").andExpect(status().isBadRequest());
        send(post("/api/admin/accounts/" + a.id() + "/unfreeze"), admin, "{}").andExpect(status().isBadRequest());

        assertThat(statusOf(closed)).isEqualTo("CLOSED");
        assertThat(statusOf(a)).isEqualTo("FROZEN"); // only the one valid freeze happened
        assertThat(auditRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from accounts where id = '00000000-0000-0000-0000-000000000001'",
                String.class)).isEqualTo("ACTIVE");
    }

    @Test
    void ifTheAuditRowCannotBeWrittenTheFreezeIsRolledBack() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");
        doThrow(new IllegalStateException("audit store down")).when(adminRepository)
                .insertAction(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> send(post("/api/admin/accounts/" + a.id() + "/freeze"), admin, REASON))
                .hasRootCauseMessage("audit store down");

        assertThat(statusOf(a)).isEqualTo("ACTIVE"); // no unaudited freeze can exist
    }

    @Test
    void theAuditLogCannotBeEditedOrDeleted() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");
        send(post("/api/admin/accounts/" + a.id() + "/freeze"), admin, REASON).andExpect(status().isNoContent());

        assertThatThrownBy(() -> jdbc.update("update admin_actions set reason = 'nothing happened'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from admin_actions")).hasMessageContaining("append-only");
        assertThat(auditRows()).isEqualTo(1);
    }

    // ---- jobs -------------------------------------------------------------------------------------------------

    @Test
    void anAdminCanRunTheInterestJobNowAndTheRunIsAudited() throws Exception {
        String admin = adminToken();
        Acc s = open(tokenFor("alice@example.com"), "SAVINGS");
        jdbc.update("update accounts set created_at = now() - interval '70 days' where id = ?::uuid", s.id());
        depositOn(s, "1000.00", Instant.now().minus(65, ChronoUnit.DAYS));

        send(post("/api/admin/jobs/interest"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.skippedByLock").value(false)).andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.credited").isNumber());
        int creditedMonths = jdbc.queryForObject("select count(*) from interest_accruals where status = 'CREDITED'",
                Integer.class);
        assertThat(creditedMonths).isGreaterThanOrEqualTo(1);

        // the second click must not pay anything twice
        send(post("/api/admin/jobs/interest"), admin, null).andExpect(jsonPath("$.credited").value(0));
        assertThat(jdbc.queryForObject("select count(*) from interest_accruals where status = 'CREDITED'", Integer.class))
                .isEqualTo(creditedMonths);

        send(get("/api/admin/audit"), admin, null).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("RUN_INTEREST"))
                .andExpect(jsonPath("$[1].detail").value("credited=" + creditedMonths + " skipped="
                        + jdbc.queryForObject("select count(*) from interest_accruals where status <> 'CREDITED'",
                        Integer.class) + " failed=0"));
        send(get("/api/admin/overview"), admin, null).andExpect(jsonPath("$.interestPaid").value(
                org.hamcrest.Matchers.not("0.00")));
        assertBooksBalance();
    }

    @Test
    void anAdminCanRunTheRecurringJobNowAndTheRunIsAudited() throws Exception {
        String admin = adminToken();
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        depositOn(a, "100.00", Instant.now().minus(1, ChronoUnit.DAYS));
        send(post("/api/recurring-payments"), alice, """
                {"sourceAccountId":"%s","destinationIban":"%s","amount":20.00,"frequency":"MONTHLY","startDate":"%s"}
                """.formatted(a.id(), b.iban(), today())).andExpect(status().isCreated());

        send(post("/api/admin/jobs/recurring"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(1)).andExpect(jsonPath("$.failed").value(0));
        send(post("/api/admin/jobs/recurring"), admin, null).andExpect(jsonPath("$.succeeded").value(0));

        assertThat(jdbc.queryForObject("select balance from accounts where id = ?::uuid", java.math.BigDecimal.class, b.id()))
                .isEqualByComparingTo("20.00");
        send(get("/api/admin/audit"), admin, null).andExpect(jsonPath("$[1].action").value("RUN_RECURRING"))
                .andExpect(jsonPath("$[1].detail").value("succeeded=1 failed=0"));
        send(get("/api/admin/overview"), admin, null).andExpect(jsonPath("$.recurring.active").value(1));
    }
}
