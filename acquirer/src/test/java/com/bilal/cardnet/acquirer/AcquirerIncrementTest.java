package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.acquirer.AcquirerService.BadRequestException;
import com.bilal.cardnet.acquirer.AcquirerService.ConflictException;
import com.bilal.cardnet.acquirer.AcquirerService.NotFoundException;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.shared.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static com.bilal.cardnet.acquirer.AcqHarness.PAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A pump that tops up: more authorized on a payment, cleared together, cancelled together. */
class AcquirerIncrementTest {

    @TempDir
    Path root;

    private static AcquirerService.IncreaseRequest more(UUID payment, String amount) {
        return new AcquirerService.IncreaseRequest(payment, amount);
    }

    private static String reference(Message first) {
        return "0100" + first.get(11) + first.get(7) + "00000" + first.get(32) + "00000000000";
    }

    // ---- asking for more ----

    @Test
    void anIncreaseIsANewAuthorizationNamingTheFirstOneAndCarryingOnlyTheExtra() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID payment = h.payApproved("50.00");
            Message first = h.issuer.received("0100").get(0);

            AcquirerService.Result result = h.service().increase(more(payment, "25.00"));

            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(result.message()).contains("Added 25.00").contains("75.00 is now authorized");
            Message extra = h.issuer.received("0100").get(1);
            assertThat(extra.get(90)).isEqualTo(reference(first));
            assertThat(extra.get(4)).as("only the extra").isEqualTo("000000002500");
            assertThat(extra.get(3)).isEqualTo("000000");
            assertThat(extra.get(11)).isNotEqualTo(first.get(11));
            assertThat(extra.get(37)).isNotEqualTo(first.get(37));
            assertThat(extra.has(52)).isFalse();
            for (int de : new int[] {2, 14, 18, 22, 25, 32, 41, 42, 43, 49}) {
                assertThat(extra.get(de)).as("DE %d", de).isEqualTo(first.get(de));
            }
            AcquirerStore.Row row = h.service().store().find(payment).orElseThrow();
            assertThat(row.incrementTotal()).isEqualByComparingTo("25.00");
            assertThat(row.authorizedTotal()).isEqualByComparingTo("75.00");
            assertThat(row.clearingAmount()).isEqualByComparingTo("75.00");
            assertThat(row.status()).isEqualTo("APPROVED");
            assertThat(h.service().store().incrementsOf(payment)).singleElement().satisfies(i -> {
                assertThat(i.status()).isEqualTo("APPROVED");
                assertThat(i.amount()).isEqualByComparingTo("25.00");
                assertThat(i.stan()).isEqualTo(extra.get(11));
            });
            assertThat(h.service().store().incrementRequest(h.service().store().incrementsOf(payment).get(0).id()).has(52)).isFalse();
        }
    }

    @Test
    void severalIncreasesAddUp() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID payment = h.payApproved("10.00");

            h.service().increase(more(payment, "20.00"));
            h.service().increase(more(payment, "5.50"));

            assertThat(h.service().store().find(payment).orElseThrow().authorizedTotal()).isEqualByComparingTo("35.50");
            assertThat(h.service().store().incrementsOf(payment)).hasSize(2);
        }
    }

    @Test
    void theBanksRefusalIsShownAndTheTotalIsUnchanged() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID payment = h.payApproved("50.00");
            h.issuer.decision = m -> m.has(90) ? "12" : "00";

            AcquirerService.Result result = h.service().increase(more(payment, "25.00"));

            assertThat(result.status()).isEqualTo("DECLINED");
            assertThat(result.responseCode()).isEqualTo("12");
            assertThat(result.message()).contains("Not added (12)").contains("cannot add to this payment");
            assertThat(h.service().store().find(payment).orElseThrow().authorizedTotal()).isEqualByComparingTo("50.00");
            assertThat(h.status(payment)).isEqualTo("APPROVED");
            assertThat(h.service().store().incrementsOf(payment)).singleElement().satisfies(i -> assertThat(i.status()).isEqualTo("DECLINED"));

            h.issuer.decision = m -> "00";
            assertThat(h.service().increase(more(payment, "25.00")).status()).as("a later try is a new request").isEqualTo("APPROVED");
        }
    }

    @Test
    void whatCannotBeAddedToIsRefusedWithoutAskingTheBank() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            UUID payment = h.payApproved("50.00");
            UUID cash = h.service().withdraw(new AcquirerService.WithdrawRequest(PAN, "10/29", "100", AcqHarness.PIN)).id();
            h.issuer.decision = m -> "51";
            UUID declined = h.service().pay(h.pay("9.00")).id();
            h.issuer.decision = m -> "00";
            UUID tipped = h.payApproved("20.00");
            h.acquirer.clearing().adjust(tipped, "22.00");
            int sent = h.issuer.received("0100").size();

            for (String bad : new String[] {"0", "-5", "abc", "1.234", "", "  ", null}) {
                assertThatThrownBy(() -> h.service().increase(more(payment, bad))).as("'" + bad + "'").isInstanceOf(BadRequestException.class);
            }
            assertThatThrownBy(() -> h.service().increase(more(UUID.randomUUID(), "5.00"))).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> h.service().increase(more(cash, "5.00"))).isInstanceOf(ConflictException.class).hasMessageContaining("cash withdrawal");
            assertThatThrownBy(() -> h.service().increase(more(declined, "5.00"))).isInstanceOf(ConflictException.class).hasMessageContaining("approved payment");
            assertThatThrownBy(() -> h.service().increase(more(tipped, "5.00"))).isInstanceOf(ConflictException.class).hasMessageContaining("final amount");

            assertThat(h.issuer.received("0100")).as("nothing reached the bank").hasSize(sent);
        }
    }

    @Test
    void aPaymentInAClearingFileOrAlreadyClearedCannotBeAddedTo() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            UUID payment = h.payApproved("50.00");
            h.acquirer.clearing().closeDay().orElseThrow();
            assertThatThrownBy(() -> h.service().increase(more(payment, "5.00"))).isInstanceOf(ConflictException.class).hasMessageContaining("batched");
            h.pumpUntil("cleared", () -> h.status(payment).equals("CLEARED"));
            assertThatThrownBy(() -> h.service().increase(more(payment, "5.00"))).isInstanceOf(ConflictException.class).hasMessageContaining("cleared");
        }
    }

    // ---- no answer ----

    @Test
    void whenNobodyAnswersTheIncreaseIsReversedOnItsOwnAndThePaymentIsUnchanged() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID payment = h.payApproved("50.00");
            h.network.chaos().acquirerResponseDelayMs(1800);

            AcquirerService.Result result = h.service().increase(more(payment, "25.00"));

            assertThat(result.status()).isEqualTo("REVERSING");
            assertThat(result.message()).contains("No answer in time").contains("Nothing was added");
            assertThat(h.service().store().find(payment).orElseThrow().authorizedTotal()).isEqualByComparingTo("50.00");
            assertThat(h.status(payment)).isEqualTo("APPROVED");
            AcquirerStore.Increment addition = h.service().store().incrementsOf(payment).get(0);
            assertThat(addition.status()).isEqualTo("REVERSING");
            assertThat(h.scalar("select timed_out::text from acq_increments")).isEqualTo("true");
            assertThat(h.count("select count(*) from pending_reversals where owner = 'ACQUIRER' and status = 'PENDING' and stan = ?", addition.stan())).isEqualTo(1);

            h.network.chaos().acquirerResponseDelayMs(0);
            Await.until("the increase is reversed", () -> h.scalar("select status from acq_increments").equals("REVERSED"));

            Message reversal = h.issuer.received("0400").get(0);
            assertThat(reversal.get(4)).as("the reversal is for the extra only").isEqualTo("000000002500");
            assertThat(reversal.get(11)).isNotEqualTo(addition.stan());
            assertThat(reversal.get(37)).isEqualTo(addition.rrn());
            assertThat(h.status(payment)).as("the payment itself is untouched").isEqualTo("APPROVED");
            assertThat(h.issuer.entryForStan(addition.stan()).status()).isEqualTo(com.bilal.cardnet.shared.testing.FakeIssuer.Status.REVERSED);
        }
    }

    // ---- void ----

    @Test
    void voidingThePaymentCancelsTheFirstAuthorizationAndEachAdditionByItsOwnAmount() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID payment = h.payApproved("50.00");
            h.service().increase(more(payment, "25.00"));
            h.service().increase(more(payment, "10.00"));

            AcquirerService.Result voided = h.service().voidSale(payment);

            assertThat(voided.status()).isEqualTo("REVERSING");
            Await.until("everything is reversed", () -> h.status(payment).equals("REVERSED")
                    && h.count("select count(*) from acq_increments where status = 'REVERSED'") == 2);
            assertThat(h.issuer.received("0400")).extracting(m -> m.get(4)).containsExactlyInAnyOrder("000000005000", "000000002500", "000000001000");
            assertThat(h.issuer.holds()).as("nothing is held any more").isZero();
        }
    }

    // ---- clearing ----

    @Test
    void thePaymentClearsForTheTotalWithTheFirstAuthorizationsCode() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            UUID payment = h.payApproved("50.00");
            String code = h.service().store().find(payment).orElseThrow().authCode();
            h.service().increase(more(payment, "25.00"));

            AcquirerClearing.BatchResult batch = h.acquirer.clearing().closeDay().orElseThrow();

            assertThat(batch.count()).as("the additions are not items of their own").isEqualTo(1);
            assertThat(batch.controlTotal()).isEqualTo(7500);
            h.pumpUntil("cleared", () -> h.status(payment).equals("CLEARED"));
            ClearingFile sent = h.clearingIssuer.received.get(0);
            assertThat(sent.presentments()).hasSize(1);
            assertThat(sent.presentments().get(0).get(4)).isEqualTo("000000007500");
            assertThat(sent.presentments().get(0).get(38)).isEqualTo(code);
            assertThat(sent.presentments().get(0).get(11)).as("the first authorization's identifiers").isEqualTo(h.issuer.received("0100").get(0).get(11));
        }
    }

    @Test
    void aTipIsCountedOnTheTotalAndALowerFinalAmountReleasesTheRest() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            UUID payment = h.payApproved("50.00");
            h.service().increase(more(payment, "25.00"));

            assertThatThrownBy(() -> h.acquirer.clearing().adjust(payment, "90.01")).isInstanceOf(BadRequestException.class).hasMessageContaining("90.00")
                    .hasMessageContaining("75.00");
            assertThat(h.acquirer.clearing().adjust(payment, "61.40").finalAmount()).as("a pump that used less than it asked for").isEqualByComparingTo("61.40");
            assertThatThrownBy(() -> h.service().increase(more(payment, "5.00"))).as("the amount is settled now").isInstanceOf(ConflictException.class);

            assertThat(h.acquirer.clearing().closeDay().orElseThrow().controlTotal()).isEqualTo(6140);
        }
    }

    @Test
    void aDayClosedWhileTheBankIsAnsweringLeavesTheAdditionOutAndCancelsIt() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            UUID payment = h.payApproved("50.00");
            h.network.chaos().acquirerResponseDelayMs(400);
            List<AcquirerService.Result> results = Collections.synchronizedList(new ArrayList<>());
            Thread asker = new Thread(() -> results.add(h.service().increase(more(payment, "25.00"))));
            asker.start();
            Thread.sleep(150);

            AcquirerClearing.BatchResult batch = h.acquirer.clearing().closeDay().orElseThrow();
            asker.join();

            assertThat(batch.controlTotal()).as("the day closed with the first amount only").isEqualTo(5000);
            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.status()).isEqualTo("REVERSING");
                assertThat(r.message()).contains("closed in the meantime");
            });
            assertThat(h.service().store().find(payment).orElseThrow().incrementTotal()).isEqualByComparingTo("0.00");
            h.network.chaos().acquirerResponseDelayMs(0);
            Await.until("the addition is reversed at the bank", () -> h.scalar("select status from acq_increments").equals("REVERSED"));
            assertThat(h.issuer.received("0400")).extracting(m -> m.get(4)).contains("000000002500");
        }
    }

    @Test
    void twoIncreasesAtTheSameMomentAreBothCountedAndNeverLost() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID payment = h.payApproved("50.00");
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            Runnable ask = () -> {
                try {
                    barrier.await();
                    h.service().increase(more(payment, "10.00"));
                } catch (Throwable e) {
                    failures.add(e);
                }
            };
            Thread a = new Thread(ask);
            Thread b = new Thread(ask);
            a.start();
            b.start();
            a.join();
            b.join();

            assertThat(failures).isEmpty();
            assertThat(h.service().store().find(payment).orElseThrow().incrementTotal()).isEqualByComparingTo("20.00");
            assertThat(h.service().store().incrementsOf(payment)).hasSize(2).extracting(i -> i.stan()).doesNotHaveDuplicates();
        }
    }

    // ---- the console ----

    @Test
    void theConsoleAddsAndShowsTheAdditionsWithoutAnyCardNumber() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID payment = h.payApproved("50.00");
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + h.acquirer.consolePort();

            HttpResponse<String> ok = http.send(post(base + "/api/increase", "{\"id\":\"" + payment + "\",\"amount\":\"25.00\"}"), HttpResponse.BodyHandlers.ofString());
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).contains("\"status\":\"APPROVED\"").contains("75.00 is now authorized");
            assertThat(http.send(post(base + "/api/increase", "{\"id\":\"" + payment + "\",\"amount\":\"0\"}"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            assertThat(http.send(post(base + "/api/increase", "{\"id\":\"" + UUID.randomUUID() + "\",\"amount\":\"5.00\"}"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            assertThat(http.send(post(base + "/api/increase", "{\"id\":\"not-an-id\",\"amount\":\"5.00\"}"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            assertThat(http.send(HttpRequest.newBuilder(URI.create(base + "/api/increase")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);

            HttpResponse<String> list = http.send(HttpRequest.newBuilder(URI.create(base + "/api/transactions")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(list.body()).contains("\"incrementTotal\":\"25.00\"").contains("\"increments\":[{\"amount\":\"25.00\",\"status\":\"APPROVED\"");
            assertThat(list.body()).doesNotContain(PAN);
        }
    }

    private static HttpRequest post(String url, String json) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }
}
