package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.card.clearing.ClearingPoster;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Incremental authorizations: a pump that asks for more on the same payment. */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=1000" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class IncrementalAuthorizationTest extends ClearingTestBase {

    /** The extra request: its own STAN and RRN, the same terminal and merchant, and DE 90 naming the first authorization. */
    private Message increment(Fixture f, Message first, String amount) {
        String de90 = "0100" + first.get(11) + first.get(7) + "00000" + first.get(32) + "00000000000";
        return purchase(f, amount).set(18, first.get(18)).set(41, first.get(41)).set(42, first.get(42)).set(90, de90);
    }

    private Message answerOf(Message request) throws Exception {
        try (Terminal terminal = new Terminal().signedOn()) {
            return terminal.exchange(request);
        }
    }

    private String answer(Message request) throws Exception {
        return code(answerOf(request));
    }

    private void wideLimits(Fixture f) {
        jdbc.update("update cards set daily_limit = 5000.00 where id = ?", f.cardId());
    }

    private String statusOf(Message request) {
        return jdbc.queryForObject("select status from authorizations where stan = ? and rrn = ?", String.class, request.get(11), request.get(37));
    }

    // ---- approval ----

    @Test
    void anIncrementIsHeldAsAnAuthorizationOfItsOwnAndAnswersWithTheFirstCode() throws Exception {
        Fixture f = fixture("inc1@example.com", "1000.00");
        Message first = authorized(f, "50.00");
        String firstCode = authCodeOf(first);
        BigDecimal firstExpiry = jdbc.queryForObject("select extract(epoch from expires_at) from authorizations where stan = ?", BigDecimal.class, first.get(11));
        Message inc = increment(f, first, "25.00");

        Message response = answerOf(inc);

        assertThat(code(response)).isEqualTo("00");
        assertThat(response.get(38)).as("the code the shop presents for clearing").isEqualTo(firstCode);
        assertThat(available(f)).isEqualByComparingTo("925.00");
        assertThat(jdbc.queryForObject("select status || '/' || amount || '/' || (parent_id = (select id from authorizations where stan = ?)) from authorizations where stan = ?",
                String.class, first.get(11), inc.get(11))).isEqualTo("AUTHORIZED/25.00/true");
        assertThat(statusOf(first)).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("select extract(epoch from expires_at) from authorizations where stan = ?", BigDecimal.class, first.get(11)))
                .as("the first hold is not extended").isEqualByComparingTo(firstExpiry);
        assertBooksBalance();
    }

    @Test
    void severalIncrementsAddUpAndEachIsHeld() throws Exception {
        Fixture f = fixture("inc2@example.com", "1000.00");
        Message first = authorized(f, "10.00");

        for (String amount : List.of("20.00", "30.00", "40.00")) {
            assertThat(answer(increment(f, first, amount))).isEqualTo("00");
        }

        assertThat(available(f)).isEqualByComparingTo("900.00");
        assertThat(jdbc.queryForObject("select count(*) from authorizations where parent_id is not null and status = 'AUTHORIZED'", Integer.class)).isEqualTo(3);
    }

    // ---- what is refused (12) ----

    @Test
    void anIncrementOfSomethingThatCannotBeAddedToIsRefusedWith12AndHoldsNothing() throws Exception {
        Fixture f = fixture("inc3@example.com", "100.00");
        Fixture other = fixture("inc3b@example.com", "1000.00");

        // no such first authorization
        Message unknown = purchase(f, "5.00");
        assertThat(answer(increment(f, unknown, "10.00"))).isEqualTo("12");
        // a first authorization that was declined
        Message declined = purchase(f, "500.00");
        assertThat(answer(declined)).isEqualTo("51");
        assertThat(answer(increment(f, declined, "10.00"))).isEqualTo("12");
        // a first authorization that was reversed
        Message reversed = authorized(f, "10.00");
        assertThat(answer(reversalOf(reversed))).isEqualTo("00");
        assertThat(answer(increment(f, reversed, "10.00"))).isEqualTo("12");
        // another terminal, another merchant
        Message live = authorized(f, "10.00");
        assertThat(answer(increment(f, live, "5.00").set(41, "TERM0002"))).isEqualTo("12");
        assertThat(answer(increment(f, live, "5.00").set(42, "MERCHANT0000002"))).isEqualTo("12");
        // another card cannot add to this card's payment
        assertThat(answer(increment(other, live, "5.00"))).isEqualTo("12");
        // an increment of an increment
        Message inc = increment(f, live, "5.00");
        assertThat(answer(inc)).isEqualTo("00");
        assertThat(answer(increment(f, inc, "5.00"))).isEqualTo("12");

        assertThat(jdbc.queryForObject("select count(*) from authorizations where parent_id is not null", Integer.class)).as("only the one good increment").isEqualTo(1);
        assertThat(available(f)).as("100 - 10 live - 5 increment").isEqualByComparingTo("85.00");
    }

    @Test
    void anIncrementOfCashOrWithAnotherProcessingCodeIsRefused() throws Exception {
        Fixture f = fixture("inc4@example.com", "1000.00");
        Message cash = withPin(purchase(f, "100.00").set(3, "010000").set(18, "6011"), f.pin());
        assertThat(answer(cash)).isEqualTo("00");
        assertThat(answer(increment(f, cash, "20.00"))).as("cash cannot be topped up").isEqualTo("12");

        Message first = authorized(f, "10.00");
        assertThat(answer(withPin(increment(f, first, "10.00").set(3, "010000").set(18, "6011"), f.pin()))).as("an increment is a purchase").isEqualTo("12");
        assertThat(available(f)).isEqualByComparingTo("890.00");
    }

    @Test
    void aFirstAuthorizationWhoseHoldRanOutCannotBeAddedTo() throws Exception {
        Fixture f = fixture("inc5@example.com", "1000.00");
        Message first = authorized(f, "10.00");
        mutableClock.set(mutableClock.instant().plus(Duration.ofDays(7)).plusSeconds(1));

        assertThat(answer(increment(f, first, "10.00"))).isEqualTo("12");
    }

    @Test
    void aMalformedReferenceIsAFormatError() throws Exception {
        Fixture f = fixture("inc6@example.com", "1000.00");
        Message first = authorized(f, "10.00");

        assertThat(answer(increment(f, first, "5.00").set(90, "0200" + first.get(11) + first.get(7) + "00000" + first.get(32) + "00000000000"))).isEqualTo("30");
        assertThat(answer(increment(f, first, "5.00").set(90, "0100" + first.get(11) + first.get(7) + "00000999999" + "00000000000"))).as("another acquirer").isEqualTo("30");
        assertThat(available(f)).isEqualByComparingTo("990.00");
    }

    // ---- the limits ----

    @Test
    void atMostFiveIncrementsAreActiveAndAReversedOneFreesItsPlace() throws Exception {
        Fixture f = fixture("inc7@example.com", "1000.00");
        Message first = authorized(f, "10.00");
        List<Message> increments = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Message inc = increment(f, first, "1.00");
            assertThat(answer(inc)).as("increment %d", i + 1).isEqualTo("00");
            increments.add(inc);
        }

        assertThat(answer(increment(f, first, "1.00"))).as("the sixth").isEqualTo("12");
        assertThat(answer(reversalOf(increments.get(2)))).isEqualTo("00");
        assertThat(answer(increment(f, first, "1.00"))).as("one place was freed").isEqualTo("00");
        assertThat(answer(increment(f, first, "1.00"))).as("and taken again").isEqualTo("12");
    }

    @Test
    void theTotalOfAnAuthorizationAndItsIncrementsStaysWithinThePerTransactionCap() throws Exception {
        Fixture f = fixture("inc8@example.com", "6000.00");
        wideLimits(f);
        Message first = authorized(f, "1000.00");

        assertThat(answer(increment(f, first, "1500.01"))).as("2500.01 in all").isEqualTo("12");
        assertThat(answer(increment(f, first, "1500.00"))).as("exactly 2500.00 in all").isEqualTo("00");
        assertThat(answer(increment(f, first, "0.01"))).as("anything more").isEqualTo("12");
    }

    @Test
    void moneyAndTheCardsDailyLimitAreCheckedForTheExtraAmount() throws Exception {
        Fixture f = fixture("inc9@example.com", "100.00");
        Message first = authorized(f, "60.00");

        assertThat(answer(increment(f, first, "40.01"))).as("only 40.00 is available").isEqualTo("51");
        assertThat(statusOf(first)).as("the first authorization is untouched by a declined increment").isEqualTo("AUTHORIZED");
        assertThat(answer(increment(f, first, "40.00"))).isEqualTo("00");
        assertThat(available(f)).isEqualByComparingTo("0.00");

        Fixture g = fixture("inc9b@example.com", "1000.00");
        jdbc.update("update cards set daily_limit = 100.00 where id = ?", g.cardId());
        Message gFirst = authorized(g, "60.00");
        assertThat(answer(increment(g, gFirst, "40.01"))).as("60 + 40.01 is over the card's 100.00").isEqualTo("61");
        assertThat(answer(increment(g, gFirst, "40.00"))).isEqualTo("00");
    }

    @Test
    void anIncrementMeetsTheOrdinaryRulesAFrozenCardAndAnAccountThatOwesMoney() throws Exception {
        Fixture f = fixture("inc10@example.com", "1000.00");
        Message first = authorized(f, "10.00");
        jdbc.update("update cards set status = 'FROZEN', freeze_reason = 'CUSTOMER' where id = ?", f.cardId());
        assertThat(answer(increment(f, first, "5.00"))).isEqualTo("57");
        jdbc.update("update cards set status = 'ACTIVE', freeze_reason = null where id = ?", f.cardId());
        jdbc.update("insert into card_receivables (id, user_id, account_id, amount, outstanding, created_at) values (gen_random_uuid(), ?::uuid, ?::uuid, 5, 5, now())",
                f.account().userId(), f.account().id());
        assertThat(answer(increment(f, first, "5.00"))).isEqualTo("05");
        assertThat(statusOf(first)).isEqualTo("AUTHORIZED");
    }

    // ---- repeats and reversals ----

    @Test
    void aRepeatedIncrementGetsTheSameAnswerAndIsHeldOnce() throws Exception {
        Fixture f = fixture("inc11@example.com", "1000.00");
        Message first = authorized(f, "50.00");
        Message inc = increment(f, first, "25.00");

        Message one = answerOf(inc);
        Message again = answerOf(inc);

        assertThat(code(one)).isEqualTo("00");
        assertThat(code(again)).isEqualTo("00");
        assertThat(again.get(38)).isEqualTo(one.get(38));
        assertThat(available(f)).as("held once").isEqualByComparingTo("925.00");
        assertThat(jdbc.queryForObject("select count(*) from authorizations where parent_id is not null", Integer.class)).isEqualTo(1);
        assertThat(answer(inc.copyWithMti("0100").set(4, cents("26.00")))).as("the same request for another amount").isEqualTo("94");
    }

    @Test
    void twoCopiesOfTheSameIncrementAtTheSameMomentAreHeldOnce() throws Exception {
        Fixture f = fixture("inc12@example.com", "1000.00");
        Message first = authorized(f, "50.00");
        Message inc = increment(f, first, "25.00");
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> answers = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Runnable send = () -> {
            try {
                barrier.await();
                answers.add(answer(inc));
            } catch (Throwable e) {
                failures.add(e);
            }
        };
        Thread a = new Thread(send);
        Thread b = new Thread(send);
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(failures).isEmpty();
        assertThat(answers).containsExactly("00", "00");
        assertThat(available(f)).isEqualByComparingTo("925.00");
        assertThat(jdbc.queryForObject("select count(*) from authorizations where parent_id is not null", Integer.class)).isEqualTo(1);
    }

    @Test
    void reversingAnIncrementReleasesOnlyThatIncrement() throws Exception {
        Fixture f = fixture("inc13@example.com", "1000.00");
        Message first = authorized(f, "50.00");
        Message inc = increment(f, first, "25.00");
        assertThat(answer(inc)).isEqualTo("00");

        assertThat(answer(reversalOf(inc))).isEqualTo("00");

        assertThat(statusOf(inc)).isEqualTo("REVERSED");
        assertThat(statusOf(first)).isEqualTo("AUTHORIZED");
        assertThat(available(f)).isEqualByComparingTo("950.00");
    }

    @Test
    void anIncrementThatArrivesAfterItsReversalIsRefusedAndHoldsNothing() throws Exception {
        Fixture f = fixture("inc14@example.com", "1000.00");
        Message first = authorized(f, "50.00");
        Message inc = increment(f, first, "25.00");

        assertThat(answer(reversalOf(inc))).as("the acquirer gave up before the request arrived").isEqualTo("00");
        assertThat(answer(inc)).isEqualTo("05");

        assertThat(available(f)).isEqualByComparingTo("950.00");
        assertThat(statusOf(first)).isEqualTo("AUTHORIZED");
    }

    @Test
    void voidingTheWholePaymentTakesAReversalForTheFirstAndOneForEachIncrement() throws Exception {
        Fixture f = fixture("inc15@example.com", "1000.00");
        Message first = authorized(f, "50.00");
        Message inc = increment(f, first, "25.00");
        assertThat(answer(inc)).isEqualTo("00");

        assertThat(answer(reversalOf(first))).as("the first alone, for its own amount").isEqualTo("00");
        assertThat(answer(reversalOf(inc))).isEqualTo("00");

        assertThat(available(f)).isEqualByComparingTo("1000.00");
        assertThat(rows("status = 'AUTHORIZED'")).isZero();
    }

    // ---- clearing ----

    @Test
    void theFirstAuthorizationIsClearedForTheTotalAndItsIncrementsAreMerged() throws Exception {
        Fixture f = fixture("inc16@example.com", "1000.00");
        Message first = authorized(f, "50.00");
        Message inc = increment(f, first, "25.00");
        Message reversedInc = increment(f, first, "5.00");
        assertThat(answer(inc)).isEqualTo("00");
        assertThat(answer(reversedInc)).isEqualTo("00");
        assertThat(answer(reversalOf(reversedInc))).isEqualTo("00");
        assertThat(available(f)).isEqualByComparingTo("925.00");

        ClearingPoster.Result result = present(presentment(first, "61.40"));

        assertThat(result.result()).isEqualTo("POSTED");
        assertThat(authorizationState(first)).isEqualTo("CAPTURED/61.40/false");
        assertThat(statusOf(inc)).as("the increment stops holding").isEqualTo("MERGED");
        assertThat(statusOf(reversedInc)).as("a reversed increment stays reversed").isEqualTo("REVERSED");
        assertThat(balanceOf(f)).isEqualByComparingTo("938.60");
        assertThat(available(f)).as("no hold is left").isEqualByComparingTo("938.60");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 61.40");
        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("61.40");
        assertThat(rows("status = 'AUTHORIZED'")).isZero();
        assertBooksBalance();
    }

    @Test
    void anAuthorizationWithoutIncrementsClearsAsBefore() throws Exception {
        Fixture f = fixture("inc17@example.com", "1000.00");
        Message first = authorized(f, "50.00");
        Message other = authorized(f, "20.00");
        Message inc = increment(f, other, "10.00");
        assertThat(answer(inc)).isEqualTo("00");

        present(presentment(first, "50.00"));

        assertThat(statusOf(inc)).as("another payment's increment is not touched").isEqualTo("AUTHORIZED");
        assertThat(statusOf(other)).isEqualTo("AUTHORIZED");
        assertThat(available(f)).isEqualByComparingTo("920.00");
    }

    @Test
    void theClearedPaymentIsDisputedForTheTotalAndTheActivityShowsTheLinks() throws Exception {
        Fixture f = fixture("inc18@example.com", "1000.00");
        Message first = authorized(f, "50.00");
        Message inc = increment(f, first, "25.00");
        assertThat(answer(inc)).isEqualTo("00");
        UUID firstId = jdbc.queryForObject("select id from authorizations where stan = ?", UUID.class, first.get(11));

        mockMvc.perform(get("/api/cards/" + f.cardId() + "/authorizations?pending=true").header("Authorization", f.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.amount=='25.00')].parentId").value(org.hamcrest.Matchers.contains(firstId.toString())))
                .andExpect(jsonPath("$.items[?(@.amount=='50.00')].parentId").value(org.hamcrest.Matchers.contains((Object) null)));

        present(presentment(first, "61.40"));

        mockMvc.perform(get("/api/cards/" + f.cardId() + "/authorizations").header("Authorization", f.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.amount=='25.00')].status").value(org.hamcrest.Matchers.contains("MERGED")))
                .andExpect(jsonPath("$.items[?(@.amount=='50.00')].capturedAmount").value(org.hamcrest.Matchers.contains("61.40")));
        mockMvc.perform(post("/api/cards/payments/" + firstId + "/dispute").header("Authorization", f.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"WRONG_AMOUNT\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value("61.40"));
    }
}
