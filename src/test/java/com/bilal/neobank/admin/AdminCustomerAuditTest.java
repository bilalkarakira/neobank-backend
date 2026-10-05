package com.bilal.neobank.admin;

import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The per-customer audit trail: what administrators did about one customer, and nobody else's. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminCustomerAuditTest extends AnalyticsTestBase {

    private static final String REASON = "{\"reason\":\"Checked with the customer\"}";

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

    private ResultActions trail(String admin, String userId) throws Exception {
        return send(get("/api/admin/customers/" + userId + "/audit"), admin, null);
    }

    private int auditRows() {
        return jdbc.queryForObject("select count(*) from admin_actions", Integer.class);
    }

    /** A row written the way the service writes it, but at a chosen moment so the order is deterministic. */
    private void insertAction(String action, String accountId, String detail, String at) {
        jdbc.update("""
                insert into admin_actions (admin_id, action, account_id, reason, detail, created_at)
                values ((select id from users where email = 'ada@example.com'), ?, ?::uuid, 'r', ?, ?::timestamptz)
                """, action, accountId, detail, at);
    }

    @Test
    void onlyAdministratorsCanReadTheTrail() throws Exception {
        String customer = tokenFor("alice@example.com");
        Acc a = open(customer, "CHECKING");

        trail(null, a.userId()).andExpect(status().isUnauthorized());
        trail(customer, a.userId()).andExpect(status().isForbidden());
    }

    @Test
    void theTrailHoldsActionsOnTheCustomersAccountsAndActionsNamingTheirEmailNewestFirst() throws Exception {
        String admin = adminToken();
        Acc alice = open(tokenFor("alice@example.com"), "CHECKING");

        send(post("/api/admin/accounts/" + alice.id() + "/freeze"), admin, REASON).andExpect(status().isNoContent());
        send(post("/api/admin/accounts/" + alice.id() + "/unfreeze"), admin, REASON).andExpect(status().isNoContent());
        send(get("/api/admin/customers/" + alice.userId()), admin, null).andExpect(status().isOk());
        insertAction("UNLOCK_LOGIN", null, "email=alice@example.com", "2999-01-01T00:00:00Z");
        insertAction("CLEAR_PROBING_BLOCK", null, "user=alice@example.com", "2999-01-02T00:00:00Z");

        trail(admin, alice.userId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].action").value("CLEAR_PROBING_BLOCK"))
                .andExpect(jsonPath("$[1].action").value("UNLOCK_LOGIN"))
                .andExpect(jsonPath("$[2].action").value("VIEW_CUSTOMER"))
                .andExpect(jsonPath("$[3].action").value("UNFREEZE_ACCOUNT"))
                .andExpect(jsonPath("$[3].accountIban").value(alice.iban()))
                .andExpect(jsonPath("$[3].reason").value("Checked with the customer"))
                .andExpect(jsonPath("$[3].adminEmail").value("ada@example.com"))
                .andExpect(jsonPath("$[4].action").value("FREEZE_ACCOUNT"));
    }

    @Test
    void anotherCustomersActionsAndBankWideJobsNeverAppear() throws Exception {
        String admin = adminToken();
        Acc alice = open(tokenFor("alice@example.com"), "CHECKING");
        Acc bob = open(tokenFor("bob@example.com"), "CHECKING");
        tokenFor("xalice@example.com"); // an email that merely ends like alice's

        send(post("/api/admin/accounts/" + bob.id() + "/freeze"), admin, REASON).andExpect(status().isNoContent());
        send(get("/api/admin/customers/" + bob.userId()), admin, null).andExpect(status().isOk());
        insertAction("UNLOCK_LOGIN", null, "email=xalice@example.com", "2999-01-01T00:00:00Z");
        insertAction("UNLOCK_LOGIN", null, "email=alice@example.com.au", "2999-01-01T00:00:01Z");
        insertAction("RUN_INTEREST", null, "succeeded=1 failed=0", "2999-01-01T00:00:02Z");
        insertAction("RUN_RECURRING", null, "succeeded=1 failed=0", "2999-01-01T00:00:03Z");

        trail(admin, alice.userId()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        trail(admin, bob.userId()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void readingTheTrailLeavesNoAuditRowOfItsOwn() throws Exception {
        String admin = adminToken();
        Acc alice = open(tokenFor("alice@example.com"), "CHECKING");

        trail(admin, alice.userId()).andExpect(status().isOk());
        trail(admin, alice.userId()).andExpect(status().isOk());

        assertThat(auditRows()).isZero();
    }

    @Test
    void unknownIdsAndAdministratorsAreNotFoundAndBadIdsAreRejected() throws Exception {
        String admin = adminToken();
        String adminId = jdbc.queryForObject("select id::text from users where email = 'ada@example.com'", String.class);

        trail(admin, UUID.randomUUID().toString()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        trail(admin, adminId).andExpect(status().isNotFound());
        trail(admin, "not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheNewestHundredAreReturned() throws Exception {
        String admin = adminToken();
        Acc alice = open(tokenFor("alice@example.com"), "CHECKING");
        for (int i = 0; i < 105; i++) {
            insertAction("UNLOCK_LOGIN", null, "email=alice@example.com", "2999-01-01T00:00:00Z".replace("00:00:00", "00:%02d:%02d".formatted(i / 60, i % 60)));
        }

        trail(admin, alice.userId()).andExpect(jsonPath("$.length()").value(100));
    }
}
