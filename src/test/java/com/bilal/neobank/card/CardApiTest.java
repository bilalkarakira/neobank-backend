package com.bilal.neobank.card;

import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import com.bilal.neobank.card.auth.Authorization;
import com.bilal.neobank.card.auth.AuthorizationRepository;
import com.bilal.neobank.card.auth.HoldService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Issuing and managing cards through the customer API. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class CardApiTest extends AnalyticsTestBase {

    @Autowired
    HoldService holdService;

    @Autowired
    AuthorizationRepository authorizations;

    private final AtomicInteger stan = new AtomicInteger();

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.header("Authorization", token);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private ResultActions issue(String token, Acc account) throws Exception {
        return send(post("/api/cards"), token, "{\"accountId\":\"%s\",\"nickname\":\"Everyday\"}".formatted(account.id()));
    }

    private String issueOk(String token, Acc account) throws Exception {
        return issue(token, account).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private void deposit(String token, Acc account, String amount) throws Exception {
        send(post("/api/accounts/" + account.id() + "/deposits").header("Idempotency-Key", "dep-" + UUID.randomUUID()),
                token, "{\"amount\":\"%s\"}".formatted(amount)).andExpect(status().isCreated());
    }

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

    private Authorization hold(UUID cardId, Acc account, String amount) {
        int n = stan.incrementAndGet();
        var request = new Authorization.Request("0100", "%06d".formatted(n), "%012d".formatted(n), "TERM0001",
                "MERCHANT0000001", "Corner Cafe", "5814", "00000000001", "000000", "051", "0101", "0101120000",
                new BigDecimal(amount), "840");
        return Authorization.approved(cardId, UUID.fromString(account.id()), "h" + "0".repeat(63), "0001", request,
                "AUTH%02d".formatted(n % 100), Instant.now(), Instant.now().plus(Duration.ofDays(7)));
    }

    @Test
    void issuingGivesATestBinLuhnPanOnceAndNeverShowsItAgain() throws Exception {
        String token = tokenFor("card1@example.com");
        Acc account = open(token, "CHECKING");

        String body = issueOk(token, account);

        String pan = JsonPath.read(body, "$.pan");
        String pin = JsonPath.read(body, "$.pin");
        assertThat(pan).hasSize(16).startsWith("999900");
        assertThat(Luhn.isValid(pan)).isTrue();
        assertThat(pin).matches("\\d{4}");
        assertThat((String) JsonPath.read(body, "$.card.maskedPan")).isEqualTo("9999 00** **** " + pan.substring(12));
        assertThat((String) JsonPath.read(body, "$.card.status")).isEqualTo("ACTIVE");
        assertThat((String) JsonPath.read(body, "$.card.dailyLimit")).isEqualTo("1000.00");
        assertThat((String) JsonPath.read(body, "$.card.expiry")).matches("(0[1-9]|1[0-2])/\\d{2}");

        String listed = api(token, "/api/cards").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(pan).doesNotContain("\"pin\"").doesNotContain("\"pan\"")
                .doesNotContain("panHash").doesNotContain("pinHash");
    }

    @Test
    void thePanAndPinAreNotInTheDatabaseInTheClear() throws Exception {
        String token = tokenFor("card2@example.com");
        Acc account = open(token, "CHECKING");
        String body = issueOk(token, account);
        String pan = JsonPath.read(body, "$.pan");
        String pin = JsonPath.read(body, "$.pin");

        String row = jdbc.queryForObject("select row_to_json(c)::text from cards c", String.class);

        assertThat(row).doesNotContain(pan).doesNotContain(pan.substring(6)); // not the whole number, not the 10 hidden digits
        assertThat(jdbc.queryForObject("select pan_hash from cards", String.class)).hasSize(64).isNotEqualTo(pan);
        assertThat(jdbc.queryForObject("select pin_hash from cards", String.class)).hasSize(64).isNotEqualTo(pin);
        assertThat(jdbc.queryForObject("select last4 from cards", String.class)).isEqualTo(pan.substring(12));
        assertThat(jdbc.queryForObject("select bin from cards", String.class)).isEqualTo("999900");
    }

    @Test
    void aCardNeedsAnActiveCheckingAccountOfTheCaller() throws Exception {
        String token = tokenFor("card3@example.com");
        Acc savings = open(token, "SAVINGS");
        Acc checking = open(token, "CHECKING");
        String other = tokenFor("card3b@example.com");
        Acc othersChecking = open(other, "CHECKING");

        issue(token, savings).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_ACCOUNT_NOT_ELIGIBLE"));
        issue(token, othersChecking).andExpect(status().isNotFound());
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", checking.id());
        issue(token, checking).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select count(*) from cards", Integer.class)).isZero();
    }

    @Test
    void aCustomerCanHaveThreeCardsAndCancellingFreesASlot() throws Exception {
        String token = tokenFor("card4@example.com");
        Acc account = open(token, "CHECKING");
        String first = issueOk(token, account);
        issueOk(token, account);
        issueOk(token, account);

        issue(token, account).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_LIMIT_REACHED"));

        send(delete("/api/cards/" + JsonPath.read(first, "$.card.id")), token, null).andExpect(status().isNoContent());
        issueOk(token, account);
    }

    @Test
    void freezingAndUnfreezingWorkAndAreIdempotent() throws Exception {
        String token = tokenFor("card5@example.com");
        Acc account = open(token, "CHECKING");
        String id = JsonPath.read(issueOk(token, account), "$.card.id");

        send(post("/api/cards/" + id + "/freeze"), token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN")).andExpect(jsonPath("$.freezeReason").value("CUSTOMER"));
        send(post("/api/cards/" + id + "/freeze"), token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        send(post("/api/cards/" + id + "/unfreeze"), token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.freezeReason").doesNotExist());
        send(post("/api/cards/" + id + "/unfreeze"), token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void aFreezeByTheBankCannotBeLiftedByTheCustomerButAPinFreezeCan() throws Exception {
        String token = tokenFor("card6@example.com");
        Acc account = open(token, "CHECKING");
        String id = JsonPath.read(issueOk(token, account), "$.card.id");

        jdbc.update("update cards set status = 'FROZEN', freeze_reason = 'ADMIN' where id = ?::uuid", id);
        send(post("/api/cards/" + id + "/unfreeze"), token, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_INVALID_STATE"));

        jdbc.update("update cards set freeze_reason = 'PIN_TRIES', pin_failures = 3 where id = ?::uuid", id);
        send(post("/api/cards/" + id + "/unfreeze"), token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.queryForObject("select pin_failures from cards where id = ?::uuid", Integer.class, id)).isZero();
    }

    @Test
    void cancellingIsFinalAndTheCardDisappears() throws Exception {
        String token = tokenFor("card7@example.com");
        Acc account = open(token, "CHECKING");
        String id = JsonPath.read(issueOk(token, account), "$.card.id");

        send(delete("/api/cards/" + id), token, null).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select status from cards where id = ?::uuid", String.class, id)).isEqualTo("CANCELLED");
        api(token, "/api/cards").andExpect(jsonPath("$.length()").value(0));
        send(post("/api/cards/" + id + "/unfreeze"), token, null).andExpect(status().isNotFound());
        send(delete("/api/cards/" + id), token, null).andExpect(status().isNotFound());
        api(token, "/api/cards/" + id + "/authorizations").andExpect(status().isNotFound());
    }

    @Test
    void otherCustomersCannotSeeOrTouchACard() throws Exception {
        String owner = tokenFor("card8@example.com");
        String intruder = tokenFor("card8b@example.com");
        Acc account = open(owner, "CHECKING");
        String id = JsonPath.read(issueOk(owner, account), "$.card.id");

        api(intruder, "/api/cards").andExpect(jsonPath("$.length()").value(0));
        send(post("/api/cards/" + id + "/freeze"), intruder, null).andExpect(status().isNotFound());
        send(post("/api/cards/" + id + "/unfreeze"), intruder, null).andExpect(status().isNotFound());
        send(delete("/api/cards/" + id), intruder, null).andExpect(status().isNotFound());
        send(put("/api/cards/" + id + "/limit"), intruder, "{\"dailyLimit\":\"10.00\"}").andExpect(status().isNotFound());
        api(intruder, "/api/cards/" + id + "/authorizations").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/cards")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select status from cards where id = ?::uuid", String.class, id)).isEqualTo("ACTIVE");
    }

    @Test
    void theDailyLimitCanBeChangedWithinTheBankCap() throws Exception {
        String token = tokenFor("card9@example.com");
        Acc account = open(token, "CHECKING");
        String id = JsonPath.read(issueOk(token, account), "$.card.id");

        send(put("/api/cards/" + id + "/limit"), token, "{\"dailyLimit\":\"250.50\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyLimit").value("250.50"));
        send(put("/api/cards/" + id + "/limit"), token, "{\"dailyLimit\":\"5000.00\"}").andExpect(status().isOk());
        for (String bad : List.of("5000.01", "0", "0.00", "-1.00")) {
            send(put("/api/cards/" + id + "/limit"), token, "{\"dailyLimit\":\"" + bad + "\"}")
                    .andExpect(status().isBadRequest());
        }
        send(put("/api/cards/" + id + "/limit"), token, "{\"dailyLimit\":\"1.234\"}").andExpect(status().isBadRequest());
        send(put("/api/cards/" + id + "/limit"), token, "{}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select daily_limit from cards where id = ?::uuid", BigDecimal.class, id))
                .isEqualByComparingTo("5000.00");
    }

    @Test
    void theCardShowsLedgerAvailableAndHeldBalancesAndSpentToday() throws Exception {
        String token = tokenFor("card10@example.com");
        Acc account = open(token, "CHECKING");
        deposit(token, account, "200.00");
        String body = issueOk(token, account);
        UUID cardId = UUID.fromString(JsonPath.read(body, "$.card.id"));
        holdService.reserve(hold(cardId, account, "45.25"));

        api(token, "/api/cards").andExpect(jsonPath("$[0].ledgerBalance").value("200.00"))
                .andExpect(jsonPath("$[0].availableBalance").value("154.75"))
                .andExpect(jsonPath("$[0].heldAmount").value("45.25"))
                .andExpect(jsonPath("$[0].spentToday").value("45.25"))
                .andExpect(jsonPath("$[0].accountName").value("Checking"));
    }

    @Test
    void pendingHoldsAndActivityAreListedWithPlainLanguageReasons() throws Exception {
        String token = tokenFor("card11@example.com");
        Acc account = open(token, "CHECKING");
        deposit(token, account, "100.00");
        UUID cardId = UUID.fromString(JsonPath.read(issueOk(token, account), "$.card.id"));
        holdService.reserve(hold(cardId, account, "10.00"));
        Authorization declined = Authorization.declined(cardId, UUID.fromString(account.id()), "h" + "1".repeat(63), "0001",
                new Authorization.Request("0100", "999999", "999999999999", "TERM0001", "MERCHANT0000001", "Big Store", "5411",
                        "00000000001", "000000", "051", "0101", "0101120000", new BigDecimal("500.00"), "840"),
                "51", Instant.now());
        authorizations.saveAndFlush(declined);

        api(token, "/api/cards/" + cardId + "/authorizations?pending=true").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.items[0].merchantName").value("Corner Cafe"))
                .andExpect(jsonPath("$.items[0].expiresAt").exists());
        api(token, "/api/cards/" + cardId + "/authorizations").andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[?(@.status=='DECLINED')].reason").value("Not enough available money."))
                .andExpect(jsonPath("$.items[?(@.status=='DECLINED')].expiresAt[0]").doesNotExist());
    }

    @Test
    void closingAnAccountEndsItsCardsAndIsRefusedWhileHoldsExist() throws Exception {
        String customer = tokenFor("card12@example.com");
        Acc account = open(customer, "CHECKING");
        String admin = adminToken();
        UUID cardId = UUID.fromString(JsonPath.read(issueOk(customer, account), "$.card.id"));
        issueOk(customer, account);
        String reason = "{\"reason\":\"Customer asked\"}";

        // a hold on an account with a zero balance cannot happen through the API, so it is planted
        authorizations.saveAndFlush(hold(cardId, account, "5.00"));
        send(post("/api/admin/accounts/" + account.id() + "/close"), admin, reason).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_HAS_HOLDS"));
        assertThat(jdbc.queryForObject("select status from accounts where id = ?::uuid", String.class, account.id())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from cards where status = 'ACTIVE'", Integer.class)).isEqualTo(2);

        jdbc.update("update authorizations set status = 'REVERSED'");
        send(post("/api/admin/accounts/" + account.id() + "/close"), admin, reason).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from cards where status = 'CANCELLED'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select detail from admin_actions where action = 'CLOSE_ACCOUNT'", String.class))
                .isEqualTo("cancelledOrders=0 cancelledCards=2");
    }
}
