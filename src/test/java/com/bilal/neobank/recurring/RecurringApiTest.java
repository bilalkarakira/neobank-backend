package com.bilal.neobank.recurring;

import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Creating, changing and cancelling standing orders through the real API. Running them is in RecurringRunTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class RecurringApiTest extends AnalyticsTestBase {

    @Autowired
    MutableClock mutableClock;

    @BeforeEach
    void realNow() {
        mutableClock.set(Instant.now());
    }

    private String body(Acc source, String iban, String amount, String frequency, LocalDate start, LocalDate end) {
        return """
                {"sourceAccountId":"%s","destinationIban":"%s","amount":%s,"description":"Rent","frequency":"%s",
                 "startDate":"%s","endDate":%s}
                """.formatted(source.id(), iban, amount, frequency, start, end == null ? "null" : "\"" + end + "\"");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.header("Authorization", token);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private ResultActions create(String token, Acc source, String iban, String amount, String frequency,
            LocalDate start, LocalDate end) throws Exception {
        return send(post("/api/recurring-payments"), token, body(source, iban, amount, frequency, start, end));
    }

    private String createOk(String token, Acc source, String iban, String amount, String frequency, LocalDate start)
            throws Exception {
        String json = create(token, source, iban, amount, frequency, start, null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    // ---- create / read ----------------------------------------------------------------------------------------

    @Test
    void createsAnOrderAndListsItForTheOwnerOnly() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(bob, "CHECKING");

        create(alice, a, b.iban(), "25.5", "MONTHLY", today().plusDays(3), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.amount").value("25.50"))
                .andExpect(jsonPath("$.destinationIban").value(b.iban()))
                .andExpect(jsonPath("$.nextRunDate").value(today().plusDays(3).toString()))
                .andExpect(jsonPath("$.description").value("Rent"))
                .andExpect(jsonPath("$.consecutiveFailures").value(0));

        api(alice, "/api/recurring-payments").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        api(bob, "/api/recurring-payments").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anotherCustomerGetsNotFoundForEverythingAboutSomeoneElsesOrder() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(bob, "CHECKING");
        String id = createOk(alice, a, b.iban(), "10.00", "WEEKLY", today());
        String path = "/api/recurring-payments/" + id;

        api(bob, path).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECURRING_NOT_FOUND"));
        send(put(path), bob, "{\"amount\":1.00}").andExpect(status().isNotFound());
        send(post(path + "/pause"), bob, null).andExpect(status().isNotFound());
        send(post(path + "/resume"), bob, null).andExpect(status().isNotFound());
        send(delete(path), bob, null).andExpect(status().isNotFound());
        api(bob, path + "/runs").andExpect(status().isNotFound());
        // and Alice's order is untouched
        api(alice, path).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.amount").value("10.00"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/recurring-payments")).andExpect(status().isUnauthorized());
    }

    // ---- validation -------------------------------------------------------------------------------------------

    @Test
    void rejectsBadSchedulesAmountsAndRecipients() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");

        create(alice, a, b.iban(), "10.00", "WEEKLY", today().minusDays(1), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.startDate").exists());
        create(alice, a, b.iban(), "10.00", "WEEKLY", today().plusDays(5), today().plusDays(4))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.endDate").exists());
        create(alice, a, b.iban(), "0", "WEEKLY", today(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.amount").exists());
        create(alice, a, b.iban(), "10.123", "WEEKLY", today(), null).andExpect(status().isBadRequest());
        send(post("/api/recurring-payments"), alice, body(a, b.iban(), "10.00", "DAILY", today(), null))
                .andExpect(status().isBadRequest());
        create(alice, a, "XX00NOTANIBAN", "10.00", "WEEKLY", today(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IBAN"));
        create(alice, a, "XX38NEOB1000999999", "10.00", "WEEKLY", today(), null).andExpect(status().is4xxClientError());
        create(alice, a, a.iban(), "10.00", "WEEKLY", today(), null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
        create(alice, a, "SYSTEM-INTEREST", "10.00", "WEEKLY", today(), null).andExpect(status().is4xxClientError());

        assertThat(jdbc.queryForObject("select count(*) from recurring_payments", Integer.class)).isZero();
    }

    @Test
    void cannotPayOutOfSomeoneElsesAccountOrWithAnUnknownCategory() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(bob, "CHECKING");

        // Bob names Alice's account as the source: indistinguishable from a missing account
        create(bob, a, b.iban(), "10.00", "WEEKLY", today(), null).andExpect(status().isNotFound());
        send(post("/api/recurring-payments"), alice, """
                {"sourceAccountId":"%s","destinationIban":"%s","amount":10.00,"frequency":"WEEKLY","startDate":"%s",
                 "categoryId":"%s"}
                """.formatted(a.id(), b.iban(), today(), UUID.randomUUID())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void anAmountAboveTheDailyLimitIsRefusedForOtherCustomersButAllowedBetweenOwnAccounts() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc checking = open(alice, "CHECKING");
        Acc savings = open(alice, "SAVINGS");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");

        create(alice, checking, b.iban(), "5000.01", "MONTHLY", today(), null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"));
        create(alice, checking, b.iban(), "5000.00", "MONTHLY", today(), null).andExpect(status().isCreated());
        create(alice, checking, savings.iban(), "6000.00", "MONTHLY", today(), null).andExpect(status().isCreated());
    }

    @Test
    void atMostTenOpenOrdersAndCancellingFreesASlot() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        String first = createOk(alice, a, b.iban(), "1.00", "WEEKLY", today());
        for (int i = 1; i < 10; i++) {
            createOk(alice, a, b.iban(), "1.00", "WEEKLY", today());
        }

        create(alice, a, b.iban(), "1.00", "WEEKLY", today(), null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECURRING_LIMIT_REACHED"));

        send(delete("/api/recurring-payments/" + first), alice, null).andExpect(status().isNoContent());
        create(alice, a, b.iban(), "1.00", "WEEKLY", today(), null).andExpect(status().isCreated());
    }

    // ---- change / pause / resume / cancel ---------------------------------------------------------------------

    @Test
    void editChangesTheAmountDescriptionAndCategoryButNotTheSchedule() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        String id = createOk(alice, a, b.iban(), "10.00", "MONTHLY", today().plusDays(2));

        send(put("/api/recurring-payments/" + id), alice, """
                {"amount":12.34,"description":"  New rent ","categoryId":"%s"}
                """.formatted(categoryId("Housing"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("12.34"))
                .andExpect(jsonPath("$.description").value("New rent"))
                .andExpect(jsonPath("$.categoryId").value(categoryId("Housing").toString()))
                .andExpect(jsonPath("$.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.nextRunDate").value(today().plusDays(2).toString()));

        send(put("/api/recurring-payments/" + id), alice, "{\"amount\":6000.00}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"));
        send(put("/api/recurring-payments/" + id), alice, "{\"amount\":0}").andExpect(status().isBadRequest());
    }

    @Test
    void pauseAndResumeFollowTheStateRules() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        String id = createOk(alice, a, b.iban(), "10.00", "WEEKLY", today().plusDays(1));
        String path = "/api/recurring-payments/" + id;

        send(post(path + "/resume"), alice, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECURRING_INVALID_STATE"));
        send(post(path + "/pause"), alice, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.autoPaused").value(false));
        send(post(path + "/pause"), alice, null).andExpect(status().isUnprocessableEntity());
        send(post(path + "/resume"), alice, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.nextRunDate").value(today().plusDays(1).toString())); // not yet due: kept
    }

    @Test
    void cancelledOrdersDisappearButOtherOrdersRemain() throws Exception {
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        String keep = createOk(alice, a, b.iban(), "10.00", "WEEKLY", today());
        String gone = createOk(alice, a, b.iban(), "20.00", "WEEKLY", today());

        send(delete("/api/recurring-payments/" + gone), alice, null).andExpect(status().isNoContent());

        api(alice, "/api/recurring-payments/" + gone).andExpect(status().isNotFound());
        send(delete("/api/recurring-payments/" + gone), alice, null).andExpect(status().isNotFound());
        api(alice, "/api/recurring-payments").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(keep));
        assertThat(jdbc.queryForObject("select status from recurring_payments where id = ?::uuid", String.class, gone))
                .isEqualTo("CANCELLED"); // kept in the database, only hidden
    }
}
