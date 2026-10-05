package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.shared.testing.Await;
import com.bilal.cardnet.shared.testing.FakeIssuer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.bilal.cardnet.acquirer.AcqHarness.CAFE;
import static com.bilal.cardnet.acquirer.AcqHarness.PAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The acquirer over real sockets: normal payments, declines, the no-answer path with its durable reversal, void and resend. */
class AcquirerFlowTest {

    // ---- the normal path ----

    @Test
    void anApprovedPaymentIsRecordedAndTheIssuerHoldsTheMoney() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result result = h.service().pay(h.pay("25.50"));

            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(result.responseCode()).isEqualTo("00");
            assertThat(result.authCode()).startsWith("FK");
            assertThat(result.message()).contains("Approved").contains(result.authCode());
            assertThat(h.issuer.holds()).isEqualTo(1);
            assertThat(h.scalar("select status || '/' || response_code || '/' || masked_pan || '/' || amount || '/' || merchant_name from acq_transactions"))
                    .isEqualTo("APPROVED/00/999900******0012/25.50/Corner Cafe");
            var sent = h.issuer.received("0100").get(0);
            assertThat(sent.get(2)).isEqualTo(PAN);
            assertThat(sent.get(14)).as("10/29 goes on the wire as YYMM").isEqualTo("2910");
            assertThat(sent.get(4)).isEqualTo("000000002550");
            assertThat(sent.get(32)).isEqualTo("123456");
            assertThat(sent.get(41)).isEqualTo("TERM0001");
            assertThat(sent.get(42)).isEqualTo(CAFE);
            assertThat(sent.get(18)).isEqualTo("5814");
            assertThat(sent.get(43).strip()).isEqualTo("Corner Cafe");
        }
    }

    @Test
    void aPaymentWithAPinSendsAPinBlockButNeverKeepsIt() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result result = h.service().pay(h.payWithPin("25.50"));

            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(h.issuer.received("0100").get(0).get(52)).as("the issuer got a PIN block").isEqualTo(com.bilal.cardnet.iso8583.PinBlock.encode("4321", PAN));
            assertThat(h.scalar("select pin_used::text from acq_transactions")).isEqualTo("true");
            assertThat(h.service().store().request(result.id()).has(52)).as("the stored copy has no PIN data").isFalse();
        }
    }

    @Test
    void aDeclineShowsThePlainReason() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.issuer.decision = m -> "51";

            AcquirerService.Result result = h.service().pay(h.pay("25.50"));

            assertThat(result.status()).isEqualTo("DECLINED");
            assertThat(result.responseCode()).isEqualTo("51");
            assertThat(result.authCode()).isNull();
            assertThat(result.message()).contains("Not enough available money");
            assertThat(h.scalar("select status || '/' || response_code || '/' || reason from acq_transactions")).isEqualTo("DECLINED/51/Not enough available money.");
            assertThat(h.issuer.holds()).isZero();
        }
    }

    @Test
    void aCardNumberNobodyOwnsIsDeclinedWithNoSuchIssuer() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result result = h.service().pay(new AcquirerService.PayRequest("4111111111111111", "10/29", "5.00", CAFE, null));

            assertThat(result.status()).isEqualTo("DECLINED");
            assertThat(result.responseCode()).isEqualTo("15");
            assertThat(result.message()).contains("No bank is known");
        }
    }

    @Test
    void whenTheIssuerIsDownTheNetworkStandsInAndThePaymentIsApproved() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.issuer.stop();
            Await.until("the link drops", () -> h.network.link().state() == com.bilal.cardnet.network.IssuerLink.State.DOWN);

            AcquirerService.Result result = h.service().pay(h.pay("25.50"));

            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(result.authCode()).startsWith("SI");
            h.issuer.restart();
            Await.until("the advice reaches the issuer", () -> h.issuer.received("0120").size() == 1);
            assertThat(h.issuer.holds()).isEqualTo(1);
        }
    }

    @Test
    void aTooBigPaymentWhileTheIssuerIsDownIsDeclinedWith91() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.issuer.stop();
            Await.until("the link drops", () -> h.network.link().state() == com.bilal.cardnet.network.IssuerLink.State.DOWN);

            AcquirerService.Result result = h.service().pay(h.pay("80.00"));

            assertThat(result.status()).isEqualTo("DECLINED");
            assertThat(result.responseCode()).isEqualTo("91");
            assertThat(result.message()).contains("could not be reached");
        }
    }

    // ---- input the terminal refuses ----

    @Test
    void badInputIsRefusedAndNothingIsRecordedOrSent() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            List<AcquirerService.PayRequest> bad = List.of(
                    new AcquirerService.PayRequest("12345", "10/29", "5.00", CAFE, null),
                    new AcquirerService.PayRequest("99990000000000AB", "10/29", "5.00", CAFE, null),
                    new AcquirerService.PayRequest(null, "10/29", "5.00", CAFE, null),
                    new AcquirerService.PayRequest(PAN, "13/29", "5.00", CAFE, null),
                    new AcquirerService.PayRequest(PAN, "1029", "5.00", CAFE, null),
                    new AcquirerService.PayRequest(PAN, null, "5.00", CAFE, null),
                    new AcquirerService.PayRequest(PAN, "10/29", "0", CAFE, null),
                    new AcquirerService.PayRequest(PAN, "10/29", "-5", CAFE, null),
                    new AcquirerService.PayRequest(PAN, "10/29", "5.123", CAFE, null),
                    new AcquirerService.PayRequest(PAN, "10/29", "abc", CAFE, null),
                    new AcquirerService.PayRequest(PAN, "10/29", "", CAFE, null),
                    new AcquirerService.PayRequest(PAN, "10/29", "99999999", CAFE, null),
                    new AcquirerService.PayRequest(PAN, "10/29", "5.00", "NO-SUCH-SHOP", null),
                    new AcquirerService.PayRequest(PAN, "10/29", "5.00", CAFE, "12"),
                    new AcquirerService.PayRequest(PAN, "10/29", "5.00", CAFE, "12ab"));
            for (AcquirerService.PayRequest request : bad) {
                assertThatThrownBy(() -> h.service().pay(request)).as(request.toString().replace(PAN, "<pan>"))
                        .isInstanceOf(AcquirerService.BadRequestException.class);
            }
            assertThat(h.count("select count(*) from acq_transactions")).isZero();
            assertThat(h.issuer.received("0100")).isEmpty();
        }
    }

    @Test
    void spacesAndDashesInTheCardNumberAreTolerated() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result result = h.service().pay(new AcquirerService.PayRequest("9999 0000 0000 0012", "10/29", "5", CAFE, " "));
            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(h.issuer.received("0100").get(0).get(2)).isEqualTo(PAN);
            assertThat(h.issuer.received("0100").get(0).has(52)).as("a blank PIN is no PIN").isFalse();
        }
    }

    // ---- no answer: the reversal ----

    @Test
    void whenTheNetworkDoesNotAnswerInTimeThePaymentIsCancelledNotGuessedAndTheReversalIsDelivered() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.network.chaos().acquirerResponseDelayMs(1800); // the network sits on its answers: longer than the acquirer waits

            AcquirerService.Result result = h.service().pay(h.pay("25.50"));

            assertThat(result.status()).as("never reported as approved").isEqualTo("REVERSING");
            assertThat(result.message()).contains("No answer in time").contains("NOT approved");
            String stan = h.issuer.received("0100").get(0).get(11);
            assertThat(h.issuer.entryForStan(stan).code()).as("the issuer HAD approved it: that is the danger being handled").isEqualTo("00");
            assertThat(h.scalar("select status || '/' || timed_out from acq_transactions")).isEqualTo("REVERSING/true");
            assertThat(h.count("select count(*) from pending_reversals where owner = 'ACQUIRER' and status = 'PENDING'")).isEqualTo(1);

            h.network.chaos().acquirerResponseDelayMs(0);
            Await.until("the payment is reversed", () -> h.status(result.id()).equals("REVERSED"));

            assertThat(h.issuer.holds()).as("the hold is gone").isZero();
            assertThat(h.issuer.received("0400").size()).isGreaterThanOrEqualTo(1);
            assertThat(h.scalar("select status from pending_reversals")).isEqualTo("DONE");
        }
    }

    @Test
    void theQueuedReversalOfAPaymentMadeWithAPinHoldsNoPinDataNotEvenEncrypted() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.network.chaos().acquirerResponseDelayMs(1800);
            AcquirerService.Result result = h.service().pay(h.payWithPin("25.50"));
            assertThat(result.status()).isEqualTo("REVERSING");

            java.util.UUID id = java.util.UUID.fromString(h.scalar("select id::text from pending_reversals"));
            byte[] ciphertext;
            try (var c = h.ds.getConnection(); var ps = c.prepareStatement("select request_ciphertext from pending_reversals"); var rs = ps.executeQuery()) {
                rs.next();
                ciphertext = rs.getBytes(1);
            }
            var queued = new com.bilal.cardnet.shared.PendingReversalStore(h.ds, h.vault)
                    .original(new com.bilal.cardnet.shared.PendingReversalStore.Due(id, ciphertext, 0, null, null));

            assertThat(queued.get(2)).isEqualTo(PAN);
            assertThat(queued.has(52)).isFalse();
            assertThat(h.issuer.received("0100").get(0).has(52)).as("but the payment itself carried it").isTrue();
        }
    }

    @Test
    void theReversalSurvivesARestartOfTheAcquirer() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.network.chaos().acquirerResponseDelayMs(1800);
            AcquirerService.Result result = h.service().pay(h.pay("25.50"));
            assertThat(result.status()).isEqualTo("REVERSING");

            h.acquirer.close(); // the acquirer "dies" with the reversal still undelivered
            h.network.chaos().acquirerResponseDelayMs(0);
            assertThat(h.status(result.id())).isEqualTo("REVERSING");
            h.acquirer = Acquirer.start(h.config, h.ds, h.vault, java.time.Clock.systemUTC());

            Await.until("the new acquirer delivers it", () -> h.status(result.id()).equals("REVERSED"));
            assertThat(h.issuer.holds()).isZero();
        }
    }

    @Test
    void withTheNetworkOutOfReachTheReversalKeepsTryingAndTheAttemptsAreCounted() throws Exception {
        try (AcqHarness h = new AcqHarness(c -> c.networkPort = freePort())) {
            AcquirerService.Result result = h.service().pay(h.pay("25.50"));

            assertThat(result.status()).isEqualTo("REVERSING");
            Await.until("several attempts were made", () -> h.count("select attempts from pending_reversals") >= 3);
            assertThat(h.status(result.id())).isEqualTo("REVERSING");
            assertThat(h.scalar("select status from pending_reversals")).isEqualTo("PENDING");
        }
    }

    @Test
    void aReversalThatCannotBeDeliveredBeforeItsDeadlineEndsAsFailedForAPersonToLookAt() throws Exception {
        try (AcqHarness h = new AcqHarness(c -> {
            c.networkPort = freePort();
            c.reversalDeadline = Duration.ofMillis(400);
        })) {
            AcquirerService.Result result = h.service().pay(h.pay("25.50"));

            Await.until("it is marked failed", () -> h.status(result.id()).equals("REVERSAL_FAILED"));

            assertThat(h.scalar("select reason from acq_transactions")).contains("needs a person");
            assertThat(h.scalar("select status || '/' || reason from pending_reversals")).isEqualTo("FAILED/deadline");
        }
    }

    // ---- an answer that is not for this payment ----

    /** A network that always answers 00 but with the wrong STAN, RRN or message type. Returns the port it listens on. */
    private static java.net.ServerSocket misbehavingNetwork(java.util.function.Function<com.bilal.cardnet.iso8583.Message, com.bilal.cardnet.iso8583.Message> mangle) throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            var packager = new com.bilal.cardnet.iso8583.Packager();
            while (!server.isClosed()) {
                try (java.net.Socket socket = server.accept()) {
                    var in = new java.io.BufferedInputStream(socket.getInputStream());
                    var out = new java.io.BufferedOutputStream(socket.getOutputStream());
                    while (true) {
                        byte[] frame = com.bilal.cardnet.iso8583.Frame.read(in);
                        if (frame == null) {
                            break;
                        }
                        var request = packager.unpack(frame);
                        var answer = request.mti().equals("0800") ? com.bilal.cardnet.shared.Messages.response(request, "00")
                                : mangle.apply(com.bilal.cardnet.shared.Messages.response(request, "00").set(38, "BAD123"));
                        com.bilal.cardnet.iso8583.Frame.write(out, packager.pack(answer));
                    }
                } catch (Exception e) {
                    // the acquirer hung up
                }
            }
        });
        return server;
    }

    @Test
    void anAnswerForAnotherPaymentOrOfTheWrongTypeIsNeverTakenAsTheAnswerAndTheReversalFollows() throws Exception {
        java.util.List<java.util.function.Function<com.bilal.cardnet.iso8583.Message, com.bilal.cardnet.iso8583.Message>> manglers = java.util.List.of(
                m -> m.set(11, "999999"),                                                        // another STAN
                m -> m.set(37, "999999999999"),                                                  // another RRN
                m -> com.bilal.cardnet.iso8583.Message.of("0210").set(11, m.get(11)).set(37, m.get(37)).set(39, "00"), // the wrong message type
                m -> m.remove(39));                                                              // no response code at all
        for (var mangle : manglers) {
            try (java.net.ServerSocket fake = misbehavingNetwork(mangle); AcqHarness h = new AcqHarness(c -> c.networkPort = fake.getLocalPort())) {
                AcquirerService.Result result = h.service().pay(h.pay("25.50"));

                assertThat(result.status()).as("never approved on the strength of someone else's answer").isEqualTo("REVERSING");
                assertThat(result.authCode()).isNull();
                assertThat(h.count("select count(*) from pending_reversals where owner = 'ACQUIRER'")).isEqualTo(1);
            }
        }
    }

    // ---- void ----

    @Test
    void voidingAnApprovedPaymentReleasesTheHoldAndShowsAsReversed() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result paid = h.service().pay(h.pay("25.50"));

            AcquirerService.Result voided = h.service().voidSale(paid.id());

            assertThat(voided.status()).isEqualTo("REVERSING");
            assertThat(voided.message()).contains("Void sent");
            Await.until("reversed", () -> h.status(paid.id()).equals("REVERSED"));
            assertThat(h.issuer.holds()).isZero();
            assertThat(h.scalar("select reason from acq_transactions")).isEqualTo("the bank confirmed the reversal");
        }
    }

    @Test
    void onlyAnApprovedPaymentCanBeVoidedAndOnlyOnce() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result paid = h.service().pay(h.pay("25.50"));
            h.service().voidSale(paid.id());

            assertThatThrownBy(() -> h.service().voidSale(paid.id())).isInstanceOf(AcquirerService.ConflictException.class)
                    .hasMessageContaining("Only an approved payment");

            h.issuer.decision = m -> "51";
            AcquirerService.Result declined = h.service().pay(h.pay("9.00"));
            assertThatThrownBy(() -> h.service().voidSale(declined.id())).isInstanceOf(AcquirerService.ConflictException.class)
                    .hasMessageContaining("declined");
            assertThatThrownBy(() -> h.service().voidSale(UUID.randomUUID())).isInstanceOf(AcquirerService.NotFoundException.class);
        }
    }

    @Test
    void twoVoidsAtTheSameMomentSendOneReversal() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result paid = h.service().pay(h.pay("25.50"));
            List<Callable<String>> voids = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                voids.add(() -> {
                    try {
                        h.service().voidSale(paid.id());
                        return "ok";
                    } catch (AcquirerService.ConflictException e) {
                        return "conflict";
                    }
                });
            }

            List<String> outcomes = raceAll(voids);

            assertThat(outcomes.stream().filter("ok"::equals).count()).isEqualTo(1);
            Await.until("reversed", () -> h.status(paid.id()).equals("REVERSED"));
            assertThat(h.count("select count(*) from pending_reversals where owner = 'ACQUIRER'")).isEqualTo(1);
        }
    }

    // ---- resend ----

    @Test
    void sendingTheLastPaymentAgainGetsTheSameAnswerAndTheBankHoldsOnce() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result paid = h.service().pay(h.pay("25.50"));

            AcquirerService.Result again = h.service().resendLast();

            assertThat(again.responseCode()).isEqualTo("00");
            assertThat(again.authCode()).isEqualTo(paid.authCode());
            assertThat(again.message()).contains("same answer again").contains(paid.authCode());
            assertThat(h.issuer.received("0100")).hasSize(2);
            assertThat(h.issuer.holds()).isEqualTo(1);
            assertThat(h.count("select count(*) from acq_transactions")).as("no new payment record").isEqualTo(1);
            assertThat(h.status(paid.id())).isEqualTo("APPROVED");
        }
    }

    @Test
    void resendingADeclineRepeatsTheDecline() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.issuer.decision = m -> "51";
            h.service().pay(h.pay("25.50"));
            h.issuer.decision = m -> "00"; // would be approved now, but the same request keeps its first answer

            AcquirerService.Result again = h.service().resendLast();

            assertThat(again.responseCode()).isEqualTo("51");
            assertThat(again.message()).contains("same answer again").contains("Not enough available money");
        }
    }

    @Test
    void thereIsNothingToResendBeforeTheFirstPayment() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            assertThatThrownBy(() -> h.service().resendLast()).isInstanceOf(AcquirerService.NotFoundException.class);
        }
    }

    // ---- many at once ----

    @Test
    void manyPaymentsAtOnceAllGetTheirOwnStanAndRrn() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            List<Callable<AcquirerService.Result>> payments = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String amount = "1.%02d".formatted(i);
                payments.add(() -> h.service().pay(h.pay(amount)));
            }

            List<AcquirerService.Result> results = raceAll(payments);

            assertThat(results).allMatch(r -> r.status().equals("APPROVED"));
            assertThat(h.issuer.holds()).isEqualTo(12);
            Set<String> keys = new HashSet<>();
            for (var m : h.issuer.received("0100")) {
                keys.add(m.get(11) + "/" + m.get(37));
            }
            assertThat(keys).hasSize(12);
            assertThat(h.count("select count(*) from acq_transactions where status = 'APPROVED'")).isEqualTo(12);
        }
    }

    // ---- helpers ----

    private static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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
