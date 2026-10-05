package com.bilal.neobank.card;

import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.card.auth.Authorization;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The read-only admin list of card authorization decisions. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class AdminAuthorizationsTest extends IssuerTestBase {

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

    private void insert(Fixture f, String stan, String amount, String code, boolean approved, Instant at, Instant expires) {
        var request = new Authorization.Request("0100", stan, "%012d".formatted(Long.parseLong(stan)), "TERM0001",
                "MERCHANT0000001", "Corner Cafe", "5814", "00000000001", "000000", "051", "0101", "0101120000",
                new BigDecimal(amount), "840");
        authorizations.saveAndFlush(approved
                ? Authorization.approved(f.cardId(), UUID.fromString(f.account().id()), "h" + "2".repeat(63), f.pan().substring(12), request, "ABC123", at, expires)
                : Authorization.declined(f.cardId(), UUID.fromString(f.account().id()), "h" + "2".repeat(63), f.pan().substring(12), request, code, at));
    }

    @Test
    void onlyAdministratorsCanSeeIt() throws Exception {
        String customer = tokenFor("authz0@example.com");

        mockMvc.perform(get("/api/admin/authorizations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/authorizations").header("Authorization", customer)).andExpect(status().isForbidden());
    }

    @Test
    void listsDecisionsNewestFirstWithAMaskedCardAndTheCustomer() throws Exception {
        Fixture f = fixture("authz1@example.com", "100.00");
        String admin = adminToken();
        Instant now = Instant.now();
        insert(f, "000001", "10.00", "00", true, now.minusSeconds(60), now.plus(Duration.ofDays(7)));
        insert(f, "000002", "500.00", "51", false, now, null);

        String body = mockMvc.perform(get("/api/admin/authorizations").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].status").value("DECLINED"))
                .andExpect(jsonPath("$.items[0].responseCode").value("51"))
                .andExpect(jsonPath("$.items[0].reason").value("Not enough available money."))
                .andExpect(jsonPath("$.items[0].customerEmail").value("authz1@example.com"))
                .andExpect(jsonPath("$.items[0].last4").value(f.pan().substring(12)))
                .andExpect(jsonPath("$.items[0].amount").value("500.00"))
                .andExpect(jsonPath("$.items[1].status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.items[1].expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(f.pan()).doesNotContain("panHash").doesNotContain("pan_hash");
    }

    @Test
    void filtersByStatusAndLastFourDigitsAndPages() throws Exception {
        Fixture f = fixture("authz2@example.com", "100.00");
        String admin = adminToken();
        Instant now = Instant.now();
        for (int i = 1; i <= 5; i++) {
            insert(f, "00000" + i, "1.00", "51", false, now.minusSeconds(i), null);
        }
        insert(f, "000009", "2.00", "00", true, now.minusSeconds(100), now.plus(Duration.ofDays(1)));

        mockMvc.perform(get("/api/admin/authorizations?status=AUTHORIZED").header("Authorization", admin))
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/admin/authorizations?status=DECLINED&size=2&page=2").header("Authorization", admin))
                .andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.size").value(2));
        mockMvc.perform(get("/api/admin/authorizations?last4=" + f.pan().substring(12)).header("Authorization", admin))
                .andExpect(jsonPath("$.total").value(6));
        String other = f.pan().endsWith("0000") ? "1111" : "0000";
        mockMvc.perform(get("/api/admin/authorizations?last4=" + other).header("Authorization", admin))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void aPaymentApprovedByTheNetworkWhileNeoBankWasUnavailableIsMarked() throws Exception {
        Fixture f = fixture("authz5@example.com", "100.00");
        String admin = adminToken();
        Instant now = Instant.now();
        var request = new Authorization.Request("0120", "000777", "000000000777", "TERM0001", "MERCHANT0000001", "Corner Cafe", "5814",
                "00000000001", "000000", "051", "0101", "0101120000", new BigDecimal("12.00"), "840");
        authorizations.saveAndFlush(Authorization.advised(f.cardId(), UUID.fromString(f.account().id()), "h" + "3".repeat(63),
                f.pan().substring(12), request, "SI4F2K", now, now.plus(Duration.ofDays(7))));
        insert(f, "000001", "10.00", "00", true, now.minusSeconds(5), now.plus(Duration.ofDays(7)));

        mockMvc.perform(get("/api/admin/authorizations").header("Authorization", admin))
                .andExpect(jsonPath("$.items[?(@.mti=='0120')].standIn").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.items[?(@.mti=='0100')].standIn").value(org.hamcrest.Matchers.contains(false)));
    }

    @Test
    void theSizeIsCappedAndBadInputIsRefused() throws Exception {
        Fixture f = fixture("authz3@example.com", "100.00");
        String admin = adminToken();

        String body = mockMvc.perform(get("/api/admin/authorizations?size=500").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(body, "$.size")).isEqualTo(50);
        for (String bad : new String[] { "last4=12", "last4=12345", "last4=abcd", "last4=12%27%20or%201=1", "status=NOPE" }) {
            mockMvc.perform(get("/api/admin/authorizations?" + bad).header("Authorization", admin))
                    .andExpect(status().isBadRequest());
        }
        assertThat(f.pan()).isNotNull();
    }

    @Test
    void aHoldPastItsExpiryIsShownAsExpiredBeforeTheNightlyJobRuns() throws Exception {
        Fixture f = fixture("authz4@example.com", "100.00");
        String admin = adminToken();
        Instant now = Instant.now();
        insert(f, "000001", "10.00", "00", true, now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(1)));

        mockMvc.perform(get("/api/admin/authorizations").header("Authorization", admin))
                .andExpect(jsonPath("$.items[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.items[0].expiresAt").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbc.queryForObject("select status from authorizations", String.class)).isEqualTo("AUTHORIZED");
    }
}
