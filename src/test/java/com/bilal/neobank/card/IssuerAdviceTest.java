package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 0120 advice: the card network approved a payment by itself while NeoBank was unavailable and tells NeoBank afterwards.
 * It is a statement of fact, so the rules do not run; the bank stands behind it.
 */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=1000" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class IssuerAdviceTest extends IssuerTestBase {

    private static final String AUTH = "SI4F2K";

    /** What the network sends: the payment again, without PIN data, with its own authorization code and 00. */
    private static Message advice(Message request, String authCode) {
        Message advice = request.copyWithMti("0120");
        advice.remove(52);
        return advice.set(38, authCode).set(39, "00");
    }

    private String answer(Message request) throws Exception {
        try (Terminal terminal = new Terminal().signedOn()) {
            return code(terminal.exchange(request));
        }
    }

    // ---- the advice itself ----

    @Test
    void anAdviceIsAnsweredWithAnAdviceResponseAndHoldsTheMoneyMarkedStandIn() throws Exception {
        Fixture f = fixture("adv1@example.com", "100.00");
        Message request = purchase(f, "25.00");

        try (Terminal terminal = new Terminal().signedOn()) {
            Message response = terminal.exchange(advice(request, AUTH));

            assertThat(response.mti()).isEqualTo("0130");
            assertThat(code(response)).isEqualTo("00");
            assertThat(response.get(11)).isEqualTo(request.get(11));
            assertThat(response.get(37)).isEqualTo(request.get(37));
            assertThat(response.has(38)).isFalse();
        }

        assertThat(jdbc.queryForObject("select status || '/' || via_stand_in || '/' || auth_code || '/' || mti || '/' || amount from authorizations",
                String.class)).isEqualTo("AUTHORIZED/true/" + AUTH + "/0120/25.00");
        assertThat(jdbc.queryForObject("select (expires_at - created_at) = interval '7 days' from authorizations", Boolean.class)).isTrue();
        assertThat(available(f)).isEqualByComparingTo("75.00");
        assertBooksBalance();
    }

    @Test
    void anAdviceNeedsASignOnAndTheElementsOfAPaymentPlusTheAuthorizationCode() throws Exception {
        Fixture f = fixture("adv2@example.com", "100.00");
        Message request = purchase(f, "25.00");

        try (Terminal unsigned = new Terminal()) {
            assertThat(code(unsigned.exchange(advice(request, AUTH)))).as("before sign-on").isEqualTo("91");
        }
        for (int missing : new int[] { 2, 3, 4, 7, 11, 13, 14, 18, 32, 37, 41, 42, 49, 38 }) {
            assertThat(answer(advice(request, AUTH).remove(missing))).as("without DE " + missing).isEqualTo("30");
        }
        assertThat(rows("true")).isZero();
    }

    @Test
    void theRulesDoNotRunBecauseTheShopWasAlreadyToldYes() throws Exception {
        Fixture f = fixture("adv3@example.com", "10.00");
        jdbc.update("update cards set daily_limit = 5.00, status = 'FROZEN', freeze_reason = 'CUSTOMER'");

        // over the balance, over the daily limit and the per-transaction cap, a blocked merchant category, a frozen card
        assertThat(answer(advice(purchase(f, "3000.00").set(18, "7995"), AUTH))).isEqualTo("00");

        assertThat(rows("status = 'AUTHORIZED' and via_stand_in")).isEqualTo(1);
        assertThat(available(f)).isEqualByComparingTo("-2990.00");
    }

    @Test
    void anAdviceBiggerThanTheBalanceIsReportedByTheBooksCheck() throws Exception {
        Fixture f = fixture("adv4@example.com", "10.00");
        assertThat(reconciliation.check().isHealthy()).isTrue();

        answer(advice(purchase(f, "50.00"), AUTH));

        var report = reconciliation.check();
        assertThat(report.isHealthy()).isFalse();
        assertThat(report.accountsHeldAboveBalance()).containsExactly(UUID.fromString(f.account().id()));
    }

    // ---- repeats and conflicts ----

    @Test
    void aRepeatedAdviceChangesNothing() throws Exception {
        Fixture f = fixture("adv5@example.com", "100.00");
        Message request = purchase(f, "25.00");

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(advice(request, AUTH)))).isEqualTo("00");
            assertThat(code(terminal.exchange(advice(request, "SIOTHR")))).isEqualTo("00");
            assertThat(code(terminal.exchange(advice(request, AUTH)))).isEqualTo("00");
        }

        assertThat(rows("true")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select auth_code from authorizations", String.class)).isEqualTo(AUTH);
        assertThat(available(f)).isEqualByComparingTo("75.00");
    }

    @Test
    void sixIdenticalAdvicesAtTheSameMomentMakeOneHold() throws Exception {
        Fixture f = fixture("adv6@example.com", "1000.00");
        for (int round = 0; round < 5; round++) {
            Message request = advice(purchase(f, "10.00"), AUTH);
            List<Callable<String>> senders = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                senders.add(() -> answer(request));
            }

            List<String> answers = raceAll(senders);

            assertThat(answers).allMatch("00"::equals);
            assertThat(rows("stan = ?", request.get(11))).as("round " + round).isEqualTo(1);
        }
        assertThat(available(f)).isEqualByComparingTo("950.00");
    }

    @Test
    void anAdviceForARequestTheIssuerAlreadyApprovedItselfChangesNothing() throws Exception {
        Fixture f = fixture("adv7@example.com", "100.00");
        Message request = purchase(f, "25.00");
        assertThat(answer(request)).isEqualTo("00");
        String realCode = jdbc.queryForObject("select auth_code from authorizations", String.class);

        assertThat(answer(advice(request, AUTH))).isEqualTo("00");

        assertThat(rows("true")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select auth_code || '/' || via_stand_in from authorizations", String.class)).isEqualTo(realCode + "/false");
        assertThat(available(f)).isEqualByComparingTo("75.00");
    }

    @Test
    void anAdviceReplacesTheIssuersEarlierDeclineOfTheSameRequestBecauseTheShopWasToldYes() throws Exception {
        Fixture f = fixture("adv8@example.com", "100.00");
        jdbc.update("update cards set daily_limit = 5.00");
        Message request = purchase(f, "25.00");
        assertThat(answer(request)).as("the issuer, in its own time, said no").isEqualTo("61");
        assertThat(available(f)).isEqualByComparingTo("100.00");

        assertThat(answer(advice(request, AUTH))).isEqualTo("00");

        assertThat(rows("true")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status || '/' || response_code || '/' || auth_code || '/' || via_stand_in from authorizations",
                String.class)).isEqualTo("AUTHORIZED/00/" + AUTH + "/true");
        assertThat(jdbc.queryForObject("select expires_at is not null and expires_at > now() from authorizations", Boolean.class)).isTrue();
        assertThat(available(f)).isEqualByComparingTo("75.00");
    }

    @Test
    void anAdviceAfterAReversalOfTheSameRequestAddsNoHold() throws Exception {
        Fixture f = fixture("adv9@example.com", "100.00");
        Message request = purchase(f, "25.00");
        assertThat(answer(request)).isEqualTo("00");
        assertThat(answer(reversalOf(request))).isEqualTo("00");

        assertThat(answer(advice(request, AUTH))).isEqualTo("00");

        assertThat(jdbc.queryForObject("select status from authorizations", String.class)).isEqualTo("REVERSED");
        assertThat(available(f)).isEqualByComparingTo("100.00");
    }

    @Test
    void anAdviceAfterAReversalThatCameFirstAddsNoHoldEither() throws Exception {
        Fixture f = fixture("adv10@example.com", "100.00");
        Message request = purchase(f, "25.00");
        assertThat(answer(reversalOf(request))).as("the reversal beat everything to the issuer: a tombstone").isEqualTo("00");

        assertThat(answer(advice(request, AUTH))).isEqualTo("00");

        assertThat(rows("status = 'AUTHORIZED'")).isZero();
        assertThat(available(f)).isEqualByComparingTo("100.00");
    }

    @Test
    void aReversalReleasesAnAdvisedHold() throws Exception {
        Fixture f = fixture("adv11@example.com", "100.00");
        Message request = purchase(f, "25.00");
        answer(advice(request, AUTH));
        assertThat(available(f)).isEqualByComparingTo("75.00");

        assertThat(answer(reversalOf(request))).isEqualTo("00");

        assertThat(available(f)).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("select status from authorizations", String.class)).isEqualTo("REVERSED");
    }

    // ---- cards and accounts the bank cannot honour ----

    @Test
    void anAdviceForAnUnknownCardIsAnsweredOkAndOnlyRecordedForTheAudit() throws Exception {
        Fixture f = fixture("adv12@example.com", "100.00");
        String unknown = "999900123456789" + Luhn.checkDigit("999900123456789");

        assertThat(answer(advice(purchaseWith(unknown, f.expiry(), "25.00", nextStan(), "000000000812"), AUTH))).isEqualTo("00");

        assertThat(jdbc.queryForObject("select status || '/' || response_code || '/' || (card_id is null) from authorizations", String.class))
                .isEqualTo("DECLINED/14/true");
        assertThat(available(f)).isEqualByComparingTo("100.00");
    }

    @Test
    void anAdviceForAnAccountThatIsNotActiveGetsNoHoldButIsAnsweredOk() throws Exception {
        Fixture f = fixture("adv13@example.com", "100.00");
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", f.account().id());

        assertThat(answer(advice(purchase(f, "25.00"), AUTH))).isEqualTo("00");

        assertThat(jdbc.queryForObject("select status || '/' || response_code from authorizations", String.class)).isEqualTo("DECLINED/05");
    }

    // ---- what the customer and the administrator see ----

    @Test
    void theCustomerSeesTheStandInMarkOnThePendingHold() throws Exception {
        Fixture f = fixture("adv14@example.com", "100.00");
        answer(advice(purchase(f, "25.00"), AUTH));
        answer(purchase(f, "5.00"));

        api(f.token(), "/api/cards/" + f.cardId() + "/authorizations?pending=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.amount=='25.00')].standIn").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.items[?(@.amount=='5.00')].standIn").value(org.hamcrest.Matchers.contains(false)));
    }

    @Test
    void anAdviceIsNotAPaymentSoVelocityDoesNotApply() throws Exception {
        Fixture f = fixture("adv15@example.com", "1000.00");
        for (int i = 0; i < 15; i++) {
            assertThat(answer(advice(purchase(f, "1.00"), "SI%04d".formatted(i)))).isEqualTo("00");
        }
        assertThat(rows("status = 'AUTHORIZED'")).isEqualTo(15);
    }

    private static <T> List<T> raceAll(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
