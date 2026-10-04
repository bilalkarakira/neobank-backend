package com.bilal.neobank.ratelimit;

import com.bilal.neobank.account.IbanGenerator;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Per-address limits on login, refresh and registration, and the guard against IBAN enumeration. */
class RateLimitFlowTest extends RateLimitTestBase {

    // ---- per address ------------------------------------------------------------------------------------------

    @Test
    void anAddressMayTryTwentyLoginsAMinuteWhateverTheEmailsAndTheTwentyFirstIsRefused() throws Exception {
        for (int i = 1; i <= 20; i++) {
            assertThat(statusOf(login("ghost" + i + "@example.com", WRONG, IP_A))).isEqualTo(401);
        }

        MvcResult refused = login("ghost21@example.com", WRONG, IP_A);

        assertThat(statusOf(refused)).isEqualTo(429);
        assertThat(codeOf(refused)).isEqualTo("RATE_LIMITED");
        assertThat(retryAfter(refused)).isEqualTo("60");
        assertThat(statusOf(login("ghost22@example.com", WRONG, IP_B))).isEqualTo(401); // another address is unaffected
    }

    @Test
    void theAddressLimitAppliesToCorrectPasswordsToo() throws Exception {
        assertThat(statusOf(register("alice@example.com", IP_B))).isEqualTo(201);
        for (int i = 0; i < 20; i++) {
            assertThat(statusOf(login("alice@example.com", PASSWORD, IP_A))).isEqualTo(200);
        }
        assertThat(statusOf(login("alice@example.com", PASSWORD, IP_A))).isEqualTo(429);
    }

    @Test
    void theLoginWindowReopensAfterAMinuteAndRetryAfterCountsDown() throws Exception {
        for (int i = 0; i < 20; i++) {
            login("ghost" + i + "@example.com", WRONG, IP_A);
        }
        after(Duration.ofSeconds(25));
        assertThat(retryAfter(login("late@example.com", WRONG, IP_A))).isEqualTo("35");

        after(Duration.ofSeconds(35));

        assertThat(statusOf(login("late@example.com", WRONG, IP_A))).isEqualTo(401);
    }

    @Test
    void refreshIsLimitedPerAddressEvenWhenEveryAttemptFails() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(statusOf(refresh(IP_A))).isEqualTo(401); // no cookie: refused, but still counted
        }

        MvcResult refused = refresh(IP_A);

        assertThat(statusOf(refused)).isEqualTo(429);
        assertThat(codeOf(refused)).isEqualTo("RATE_LIMITED");
        assertThat(statusOf(refresh(IP_B))).isEqualTo(401);
    }

    @Test
    void aRequestWithoutTheCsrfHeaderIsNotCountedAgainstTheRefreshLimit() throws Exception {
        for (int i = 0; i < 40; i++) {
            assertThat(statusOf(mockMvc.perform(post("/api/auth/refresh").with(from(IP_A))).andReturn())).isEqualTo(403);
        }
        assertThat(statusOf(refresh(IP_A))).isEqualTo(401); // not 429: the 403s cost nothing
    }

    @Test
    void anAddressMayCreateTenAccountsAnHour() throws Exception {
        for (int i = 1; i <= 10; i++) {
            assertThat(statusOf(register("user" + i + "@example.com", IP_A))).isEqualTo(201);
        }

        MvcResult refused = register("user11@example.com", IP_A);

        assertThat(statusOf(refused)).isEqualTo(429);
        assertThat(retryAfter(refused)).isEqualTo("3600");
        assertThat(statusOf(register("user11@example.com", IP_B))).isEqualTo(201);
        after(Duration.ofHours(1));
        assertThat(statusOf(register("user12@example.com", IP_A))).isEqualTo(201);
    }

    @Test
    void repeatedRegistrationOfTheSameEmailCountsToo() throws Exception {
        for (int i = 0; i < 10; i++) {
            register("same@example.com", IP_A); // 201 once, then 409s
        }
        assertThat(statusOf(register("same@example.com", IP_A))).isEqualTo(429);
    }

    // ---- recipient lookup and enumeration ---------------------------------------------------------------------

    private String realIban(String bearer) throws Exception {
        return JsonPath.read(openAccount(bearer), "$.iban");
    }

    /** A checksum-valid IBAN that no account has. */
    private static String missingIban(int n) {
        return IbanGenerator.generate(900_000L + n);
    }

    @Test
    void aUserMayLookUpTwentyRecipientsAMinute() throws Exception {
        String alice = bearerFor("alice@example.com");
        String iban = realIban(bearerFor("bob@example.com"));
        for (int i = 0; i < 20; i++) {
            assertThat(statusOf(lookup(alice, iban))).isEqualTo(200);
        }

        MvcResult refused = lookup(alice, iban);

        assertThat(statusOf(refused)).isEqualTo(429);
        assertThat(codeOf(refused)).isEqualTo("RATE_LIMITED");
        after(Duration.ofMinutes(1));
        assertThat(statusOf(lookup(alice, iban))).isEqualTo(200);
    }

    @Test
    void ordinaryLookupsOfRealAccountsNeverCountAsProbing() throws Exception {
        String alice = bearerFor("alice@example.com");
        String iban = realIban(bearerFor("bob@example.com"));
        for (int i = 0; i < 15; i++) {
            assertThat(statusOf(lookup(alice, iban))).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("select count(*) from rate_limits where bucket_key like 'probe:%'", Integer.class))
                .isZero();
    }

    @Test
    void tenMissesBlockFurtherRecipientLookupsEvenForARealIban() throws Exception {
        String alice = bearerFor("alice@example.com");
        String bob = bearerFor("bob@example.com");
        String realIban = realIban(bob);
        for (int i = 0; i < 10; i++) {
            MvcResult miss = lookup(alice, missingIban(i));
            assertThat(statusOf(miss)).isEqualTo(404);
            assertThat(codeOf(miss)).isEqualTo("RECIPIENT_NOT_FOUND");
        }

        MvcResult blocked = lookup(alice, realIban);

        assertThat(statusOf(blocked)).isEqualTo(429);
        assertThat(retryAfter(blocked)).isEqualTo("600"); // the 10-minute probing window
        assertThat(statusOf(lookup(bob, realIban))).isEqualTo(200); // another user is not punished
        after(Duration.ofMinutes(10));
        assertThat(statusOf(lookup(alice, realIban))).isEqualTo(200);
    }

    @Test
    void malformedIbansCountAsMissesToo() throws Exception {
        String alice = bearerFor("alice@example.com");
        for (int i = 0; i < 10; i++) {
            assertThat(statusOf(lookup(alice, "NOT-AN-IBAN-" + i))).isEqualTo(400);
        }
        assertThat(statusOf(lookup(alice, "NOT-AN-IBAN-11"))).isEqualTo(429);
    }

    @Test
    void transfersToUnknownIbansCountAsMissesAndAreBlockedOnceTheLimitIsReached() throws Exception {
        String alice = bearerFor("alice@example.com");
        String account = openAccount(alice);
        String accountId = JsonPath.read(account, "$.id");
        String realIban = realIban(bearerFor("bob@example.com"));
        int n = 0;
        for (; n < 10; n++) {
            MvcResult miss = mockMvc.perform(post("/api/transfers").header("Authorization", alice)
                    .header("Idempotency-Key", "probe-transfer-" + n).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceAccountId\":\"%s\",\"destinationIban\":\"%s\",\"amount\":1.00}"
                            .formatted(accountId, missingIban(n)))).andReturn();
            assertThat(statusOf(miss)).isEqualTo(404);
        }

        MvcResult blocked = mockMvc.perform(post("/api/transfers").header("Authorization", alice)
                .header("Idempotency-Key", "probe-transfer-final").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAccountId\":\"%s\",\"destinationIban\":\"%s\",\"amount\":1.00}"
                        .formatted(accountId, realIban))).andReturn();

        assertThat(statusOf(blocked)).isEqualTo(429);
        assertThat(statusOf(lookup(alice, realIban))).isEqualTo(429); // lookups share the same probing counter
    }

    @Test
    void aMissInsideATransactionalRequestStaysCountedEvenThoughTheRequestFails() throws Exception {
        String alice = bearerFor("alice@example.com");
        String accountId = JsonPath.read(openAccount(alice), "$.id");

        MvcResult failed = mockMvc.perform(post("/api/recurring-payments").header("Authorization", alice)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceAccountId":"%s","destinationIban":"%s","amount":5.00,"frequency":"MONTHLY","startDate":"2026-10-05"}
                        """.formatted(accountId, missingIban(1)))).andReturn();

        assertThat(statusOf(failed)).isEqualTo(404);
        // the request rolled back, but the attempt must not have been rolled back with it
        assertThat(jdbc.queryForObject("select coalesce(sum(hits), 0) from rate_limits where bucket_key like 'probe:%'",
                Integer.class)).isEqualTo(1);
    }
}
