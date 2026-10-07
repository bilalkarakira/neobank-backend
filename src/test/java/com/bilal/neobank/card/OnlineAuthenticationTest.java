package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An online payment above 30.00 needs the customer's approval of exactly that payment: declined 1A with a reference in DE 48, the customer
 * answers in the app, the shop tries again with the reference as proof. The proof pays once.
 */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=50" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class OnlineAuthenticationTest extends IssuerTestBase {

    private Message ask(Message request) throws Exception {
        try (Terminal terminal = new Terminal().signedOn()) {
            return terminal.exchange(request);
        }
    }

    private String answer(Message request) throws Exception {
        return code(ask(request));
    }

    private Message proven(Fixture f, String amount, String reference) {
        return online(f, amount, f.cvv()).set(48, "3DS|" + reference);
    }

    /** Asks for a payment that needs approval and returns the reference from the answer. */
    private String challenge(Fixture f, String amount) throws Exception {
        Message response = ask(online(f, amount, f.cvv()));
        assertThat(code(response)).isEqualTo("1A");
        assertThat(response.get(48)).matches("3DS\\|[A-Z2-9]{6}");
        return response.get(48).substring(4);
    }

    private UUID idOf(String reference) {
        return jdbc.queryForObject("select id from card_authentications where reference = ?", UUID.class, reference);
    }

    private ResultActions approve(Fixture f, String reference) throws Exception {
        return mockMvc.perform(post("/api/cards/authentications/" + idOf(reference) + "/approve").header("Authorization", f.token()));
    }

    private void approved(Fixture f, String reference) throws Exception {
        approve(f, reference).andExpect(status().isNoContent());
    }

    private int authentications() {
        return jdbc.queryForObject("select count(*) from card_authentications", Integer.class);
    }

    // ---- when it is needed ----

    @Test
    void thirtyNeedsOnlyTheCodeAndAnythingAboveItNeedsTheCustomersApproval() throws Exception {
        Fixture f = fixture("sca1@example.com", "1000.00");

        assertThat(answer(online(f, "30.00", f.cvv()))).as("exactly the threshold").isEqualTo("00");
        assertThat(authentications()).isZero();

        Message response = ask(online(f, "30.01", f.cvv()));
        assertThat(code(response)).as("one cent above").isEqualTo("1A");
        assertThat(authentications()).isEqualTo(1);
    }

    @Test
    void aShopPaymentInATerminalAndAnAmountBelowTheThresholdNeedNoApproval() throws Exception {
        Fixture f = fixture("sca2@example.com", "1000.00");

        assertThat(answer(purchase(f, "500.00"))).as("a terminal").isEqualTo("00");
        assertThat(answer(online(f, "5.00", f.cvv()))).isEqualTo("00");
        assertThat(authentications()).isZero();
    }

    @Test
    void theChallengeIsAPendingRowForExactlyThisPaymentANoticeAndADeclinedAuthorizationWithoutAHold() throws Exception {
        Fixture f = fixture("sca3@example.com", "1000.00");
        Instant now = Instant.parse("2026-10-07T10:00:00Z");
        mutableClock.set(now);

        String reference = challenge(f, "80.00");

        assertThat(jdbc.queryForObject("""
                select status || '/' || amount || '/' || merchant_id || '/' || merchant_name || '/' || (card_id = ?) || '/' ||
                       (expires_at - created_at = interval '5 minutes') || '/' || (used_at is null)
                from card_authentications where reference = ?
                """, String.class, f.cardId(), reference)).isEqualTo("PENDING/80.00/MERCHANT0000001/Corner Cafe Springfield/true/true/true");
        assertThat(jdbc.queryForObject("select status || '/' || response_code from authorizations", String.class)).isEqualTo("DECLINED/1A");
        assertThat(available(f)).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject("select body from notifications where kind = 'CARD_AUTH_REQUESTED'", String.class))
                .contains("Corner Cafe Springfield").contains("80.00").contains(reference).contains(f.pan().substring(12));
    }

    @Test
    void theCodeIsCheckedBeforeTheApprovalIsAsked() throws Exception {
        Fixture f = fixture("sca4@example.com", "1000.00");
        String wrong = f.cvv().equals("000") ? "111" : "000";

        Message response = ask(online(f, "80.00", wrong));

        assertThat(code(response)).isEqualTo("N7");
        assertThat(response.has(48)).isFalse();
        assertThat(authentications()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where kind = 'CARD_AUTH_REQUESTED'", Integer.class)).isZero();
    }

    // ---- the good path ----

    @Test
    void anApprovedRequestPaysTheOnePaymentItWasMadeFor() throws Exception {
        Fixture f = fixture("sca5@example.com", "1000.00");
        String reference = challenge(f, "80.00");
        approved(f, reference);

        Message retry = ask(proven(f, "80.00", reference));

        assertThat(code(retry)).isEqualTo("00");
        assertThat(retry.has(48)).isFalse();
        assertThat(available(f)).isEqualByComparingTo("920.00");
        assertThat(jdbc.queryForObject("select (used_at is not null) || '/' || status from card_authentications where reference = ?", String.class, reference))
                .isEqualTo("true/APPROVED");
        assertThat(authentications()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from authorizations where status = 'AUTHORIZED' and entry_mode = '812'", Integer.class)).isEqualTo(1);
    }

    @Test
    void theSameApprovalCannotPayTwiceAndTheSecondTryGetsANewChallenge() throws Exception {
        Fixture f = fixture("sca6@example.com", "1000.00");
        String reference = challenge(f, "80.00");
        approved(f, reference);
        assertThat(answer(proven(f, "80.00", reference))).isEqualTo("00");

        Message again = ask(proven(f, "80.00", reference));

        assertThat(code(again)).isEqualTo("1A");
        assertThat(again.get(48)).startsWith("3DS|").isNotEqualTo("3DS|" + reference);
        assertThat(available(f)).as("only one payment was held").isEqualByComparingTo("920.00");
        assertThat(authentications()).isEqualTo(2);
    }

    @Test
    void oneApprovalPaysOnceEvenWhenTwoRequestsCarryItAtTheSameMoment() throws Exception {
        Fixture f = fixture("sca7@example.com", "1000.00");
        String reference = challenge(f, "80.00");
        approved(f, reference);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> tries = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Message request = proven(f, "80.00", reference);
                tries.add(() -> answer(request));
            }
            List<String> codes = new ArrayList<>();
            for (Future<String> result : pool.invokeAll(tries)) {
                codes.add(result.get());
            }
            assertThat(codes).containsExactlyInAnyOrder("00", "1A");
        } finally {
            pool.shutdownNow();
        }
        assertThat(available(f)).isEqualByComparingTo("920.00");
        assertThat(rows("status = 'AUTHORIZED' and entry_mode = '812'")).isEqualTo(1);
    }

    // ---- every way a proof can be wrong ----

    @Test
    void aMissingOrUnknownOrMalformedProofIsAChallengeAgain() throws Exception {
        Fixture f = fixture("sca8@example.com", "1000.00");

        assertThat(code(ask(online(f, "80.00", f.cvv())))).as("no DE 48").isEqualTo("1A");
        assertThat(code(ask(proven(f, "80.00", "ZZZZZZ")))).as("unknown reference").isEqualTo("1A");
        assertThat(code(ask(online(f, "80.00", f.cvv()).set(48, "HELLO")))).as("not a proof").isEqualTo("1A");
        assertThat(code(ask(online(f, "80.00", f.cvv()).set(48, "3DS|")))).as("empty reference").isEqualTo("1A");
        assertThat(available(f)).isEqualByComparingTo("1000.00");
    }

    @Test
    void aProofIsForOneCardOneAmountAndOneShop() throws Exception {
        Fixture f = fixture("sca9@example.com", "1000.00");
        Fixture other = fixture("sca9b@example.com", "1000.00");
        String reference = challenge(f, "80.00");
        approved(f, reference);

        assertThat(code(ask(proven(other, "80.00", reference)))).as("another card").isEqualTo("1A");
        assertThat(code(ask(proven(f, "80.01", reference)))).as("a cent more").isEqualTo("1A");
        assertThat(code(ask(proven(f, "79.99", reference)))).as("a cent less").isEqualTo("1A");
        assertThat(code(ask(proven(f, "80.00", reference).set(42, "MERCHANT0000002")))).as("another shop").isEqualTo("1A");
        assertThat(jdbc.queryForObject("select used_at is null from card_authentications where reference = ?", Boolean.class, reference))
                .as("none of those used it up").isTrue();

        assertThat(code(ask(proven(f, "80.00", reference)))).as("and the right one still works").isEqualTo("00");
    }

    @Test
    void aRequestThatIsStillWaitingIsNotAProof() throws Exception {
        Fixture f = fixture("sca10@example.com", "1000.00");
        String reference = challenge(f, "80.00");

        Message retry = ask(proven(f, "80.00", reference));

        assertThat(code(retry)).isEqualTo("1A");
        assertThat(retry.get(48)).isNotEqualTo("3DS|" + reference);
        assertThat(available(f)).isEqualByComparingTo("1000.00");
    }

    @Test
    void aRejectedRequestIsRefusedAndAsksNothingMore() throws Exception {
        Fixture f = fixture("sca11@example.com", "1000.00");
        String reference = challenge(f, "80.00");
        mockMvc.perform(post("/api/cards/authentications/" + idOf(reference) + "/reject").header("Authorization", f.token())).andExpect(status().isNoContent());

        Message retry = ask(proven(f, "80.00", reference));

        assertThat(code(retry)).isEqualTo("05");
        assertThat(retry.has(48)).isFalse();
        assertThat(authentications()).as("no new request").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notifications where kind = 'CARD_AUTH_REQUESTED'", Integer.class)).isEqualTo(1);
        assertThat(code(ask(proven(f, "81.00", reference)))).as("a rejection for another amount does not apply: a new challenge").isEqualTo("1A");
    }

    @Test
    void anApprovalIsValidUntilItsExpiryAndNotAtItsExpiry() throws Exception {
        Fixture f = fixture("sca12@example.com", "1000.00");
        Instant start = Instant.now();
        mutableClock.set(start);
        String reference = challenge(f, "80.00");
        approved(f, reference);
        Instant expiresAt = jdbc.queryForObject("select expires_at from card_authentications where reference = ?", java.sql.Timestamp.class, reference).toInstant();
        assertThat(Duration.between(start.truncatedTo(java.time.temporal.ChronoUnit.MICROS), expiresAt)).isEqualTo(Duration.ofMinutes(5));

        mutableClock.set(expiresAt);
        assertThat(code(ask(proven(f, "80.00", reference)))).as("at the exact second it expires").isEqualTo("1A");

        mutableClock.set(expiresAt.minusNanos(1000));
        assertThat(code(ask(proven(f, "80.00", reference)))).as("a microsecond before").isEqualTo("00");
    }

    @Test
    void anApprovedRequestIsNotUsedUpByAPaymentThatCouldNotBeHeld() throws Exception {
        Fixture f = fixture("sca13@example.com", "50.00");
        String reference = challenge(f, "80.00");
        approved(f, reference);

        assertThat(code(ask(proven(f, "80.00", reference)))).as("not enough money").isEqualTo("51");
        assertThat(jdbc.queryForObject("select used_at is null from card_authentications where reference = ?", Boolean.class, reference)).isTrue();

        deposit(f.token(), f.account(), "100.00");
        assertThat(code(ask(proven(f, "80.00", reference)))).as("after a deposit the same approval pays").isEqualTo("00");
    }

    @Test
    void theCodeIsStillCheckedWhenTheProofIsRight() throws Exception {
        Fixture f = fixture("sca14@example.com", "1000.00");
        String reference = challenge(f, "80.00");
        approved(f, reference);
        String wrong = f.cvv().equals("000") ? "111" : "000";

        assertThat(code(ask(online(f, "80.00", wrong).set(48, "3DS|" + reference)))).isEqualTo("N7");
        assertThat(jdbc.queryForObject("select used_at is null from card_authentications where reference = ?", Boolean.class, reference)).isTrue();
    }

    // ---- additions are not allowed to online payments ----

    @Test
    void anOnlinePaymentCannotBeAddedToAndTheAttemptIsNotCountedAgainstTheCard() throws Exception {
        Fixture f = fixture("sca15@example.com", "1000.00");
        Message first = online(f, "20.00", f.cvv());
        assertThat(answer(first)).isEqualTo("00");
        String de90 = "0100" + first.get(11) + first.get(7) + "00000" + first.get(32) + "00000000000";

        Message addition = purchase(f, "10.00").set(90, de90);
        assertThat(answer(addition)).as("as a terminal addition").isEqualTo("12");
        Message onlineAddition = online(f, "10.00", null).set(90, de90);
        assertThat(answer(onlineAddition)).as("as an online addition").isEqualTo("12");

        assertThat(jdbc.queryForObject("select pin_failures from cards where id = ?", Integer.class, f.cardId())).isZero();
        assertThat(available(f)).isEqualByComparingTo("980.00");
    }

    // ---- the customer's side ----

    @Test
    void theCustomerSeesOnlyOwnOpenRequestsAndAnsweredOnesDisappear() throws Exception {
        Fixture f = fixture("sca16@example.com", "1000.00");
        Fixture stranger = fixture("sca16b@example.com", "1000.00");
        String mine = challenge(f, "80.00");
        String answered = challenge(f, "90.00");
        challenge(stranger, "70.00");
        approved(f, answered);

        String body = mockMvc.perform(get("/api/cards/authentications").header("Authorization", f.token())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$[*].reference")).containsExactly(mine);
        assertThat(JsonPath.<String>read(body, "$[0].amount")).isEqualTo("80.00");
        assertThat(JsonPath.<String>read(body, "$[0].merchantName")).isEqualTo("Corner Cafe Springfield");
        assertThat(JsonPath.<String>read(body, "$[0].cardLast4")).isEqualTo(f.pan().substring(12));
        assertThat(body).doesNotContain(f.pan()).doesNotContain("cardId");

        mutableClock.set(Instant.now().plus(Duration.ofMinutes(6)));
        assertThat(mockMvc.perform(get("/api/cards/authentications").header("Authorization", f.token())).andReturn().getResponse().getContentAsString())
                .as("an expired request is not listed").isEqualTo("[]");
    }

    @Test
    void onlyTheOwnerAnswersAndOnlyOnceAndOnlyWhileItIsOpen() throws Exception {
        Fixture f = fixture("sca17@example.com", "1000.00");
        Fixture stranger = fixture("sca17b@example.com", "1000.00");
        String reference = challenge(f, "80.00");
        UUID id = idOf(reference);

        mockMvc.perform(post("/api/cards/authentications/" + id + "/approve").header("Authorization", stranger.token())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/cards/authentications/" + id + "/reject").header("Authorization", stranger.token())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/cards/authentications/" + UUID.randomUUID() + "/approve").header("Authorization", f.token())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/cards/authentications/" + id + "/approve")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select status from card_authentications where id = ?", String.class, id)).isEqualTo("PENDING");

        mockMvc.perform(post("/api/cards/authentications/" + id + "/approve").header("Authorization", f.token())).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/cards/authentications/" + id + "/approve").header("Authorization", f.token())).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/cards/authentications/" + id + "/reject").header("Authorization", f.token())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_INVALID_STATE"));
        assertThat(jdbc.queryForObject("select status from card_authentications where id = ?", String.class, id)).as("the first answer stands").isEqualTo("APPROVED");

        String late = challenge(f, "85.00");
        mutableClock.set(Instant.now().plus(Duration.ofMinutes(6)));
        mockMvc.perform(post("/api/cards/authentications/" + idOf(late) + "/approve").header("Authorization", f.token())).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select status from card_authentications where reference = ?", String.class, late)).as("too late").isEqualTo("PENDING");
    }

    @Test
    void aFrozenCardGetsNoChallenge() throws Exception {
        Fixture f = fixture("sca18@example.com", "1000.00");
        jdbc.update("update cards set status = 'FROZEN', freeze_reason = 'CUSTOMER' where id = ?", f.cardId());

        assertThat(answer(online(f, "80.00", f.cvv()))).isEqualTo("57");
        assertThat(authentications()).isZero();
    }

}
