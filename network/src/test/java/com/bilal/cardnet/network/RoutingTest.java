package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.Messages;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.bilal.cardnet.network.NetHarness.OTHER_PAN;
import static com.bilal.cardnet.network.NetHarness.PAN;
import static com.bilal.cardnet.network.NetHarness.code;
import static org.assertj.core.api.Assertions.assertThat;

/** Normal operation over real sockets: routing, sign-on rules, answers passed through, duplicates, hostile input. */
class RoutingTest {

    @Test
    void anApprovalFromTheIssuerIsPassedBackUnchanged() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            Message request = h.purchase(PAN, "25.50");

            Message response = acquirer.exchange(request);

            assertThat(response.mti()).isEqualTo("0110");
            assertThat(code(response)).isEqualTo("00");
            assertThat(response.get(38)).startsWith("FK");
            for (int field : Messages.ECHOED) {
                assertThat(response.get(field)).as("DE " + field).isEqualTo(request.get(field));
            }
            assertThat(h.issuer.holds()).isEqualTo(1);
            assertThat(h.issuer.received("0100")).hasSize(1);
            assertThat(h.issuer.received("0100").get(0)).as("the request reaches the issuer unchanged").isEqualTo(request);
            assertThat(h.scalar("select route || '/' || answer_code || '/' || masked_pan || '/' || amount from net_transactions where mti = '0100'"))
                    .isEqualTo("ISSUER/00/999900******0012/25.50");
        }
    }

    @Test
    void aDeclineFromTheIssuerIsPassedBackAsItIs() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            h.issuer.decision = m -> "51";

            Message response = acquirer.exchange(h.purchase(PAN, "25.50"));

            assertThat(code(response)).isEqualTo("51");
            assertThat(response.has(38)).isFalse();
            assertThat(h.issuer.holds()).isZero();
            assertThat(h.count("select count(*) from net_standin_advices")).as("a decline is never stood in for").isZero();
        }
    }

    @Test
    void aFinancialMessageIsRoutedToo() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            Message request = Message.of("0200");
            h.purchase(PAN, "5.00").fields().forEach(request::set);

            Message response = acquirer.exchange(request);

            assertThat(response.mti()).isEqualTo("0210");
            assertThat(code(response)).isEqualTo("00");
        }
    }

    @Test
    void aCardNumberNobodyOwnsGetsNoSuchIssuerAndNothingIsForwarded() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            Message response = acquirer.exchange(h.purchase("4111111111111111", "5.00"));

            assertThat(code(response)).isEqualTo("15");
            assertThat(h.issuer.received("0100")).isEmpty();
            assertThat(h.scalar("select route || '/' || answer_code from net_transactions where mti = '0100'")).isEqualTo("REFUSED/15");
        }
    }

    @Test
    void aRequestMissingARequiredElementIsAFormatErrorAndNotForwarded() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            for (int missing : new int[] { 2, 3, 4, 7, 11, 13, 18, 32, 37, 41, 42, 49 }) {
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00").remove(missing)))).as("without DE " + missing).isEqualTo("30");
            }
            assertThat(h.issuer.received("0100")).isEmpty();
        }
    }

    @Test
    void aMalformedReversalIsAFormatError() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            Message original = h.purchase(PAN, "5.00");
            for (int missing : new int[] { 2, 4, 13, 37, 41, 42, 49, 90 }) {
                Message reversal = Messages.reversalOf(original, h.nextStan(), "1005120500").remove(missing);
                assertThat(code(acquirer.exchange(reversal))).as("without DE " + missing).isEqualTo("30");
            }
            Message wrongType = Messages.reversalOf(original, h.nextStan(), "1005120500")
                    .set(90, "0800" + original.get(11) + original.get(7) + "00000123456" + "00000000000");
            assertThat(code(acquirer.exchange(wrongType))).isEqualTo("30");
            assertThat(h.issuer.received("0400")).isEmpty();
        }
    }

    @Test
    void paymentsAndReversalsNeedASignOnOnTheirOwnConnection() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer()) {
            Message request = h.purchase(PAN, "5.00");
            assertThat(code(acquirer.exchange(request))).as("before sign-on").isEqualTo("91");
            assertThat(code(acquirer.exchange(Messages.reversalOf(request, h.nextStan(), "1005120500")))).isEqualTo("91");
            assertThat(h.issuer.received("0100")).isEmpty();

            assertThat(code(acquirer.exchange(Messages.signOn("000001", "1005120000")))).isEqualTo("00");
            assertThat(code(acquirer.exchange(request))).isEqualTo("00");

            assertThat(code(acquirer.exchange(Messages.signOff("000002", "1005120000")))).isEqualTo("00");
            assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00")))).as("after sign-off").isEqualTo("91");
        }
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer fresh = h.acquirer()) {
            assertThat(code(fresh.exchange(h.purchase(PAN, "5.00")))).as("another connection has not signed on").isEqualTo("91");
        }
    }

    @Test
    void echoAndUnknownNetworkCodesAreAnsweredAsTheIssuerDoes() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer()) {
            Message echo = acquirer.exchange(Messages.echo("000005", "1005120000"));
            assertThat(echo.mti()).isEqualTo("0810");
            assertThat(code(echo)).isEqualTo("00");
            assertThat(echo.get(11)).isEqualTo("000005");
            assertThat(echo.get(70)).isEqualTo("301");

            assertThat(code(acquirer.exchange(Message.of("0800").set(7, "1005120000").set(11, "000006").set(70, "999")))).isEqualTo("12");
            assertThat(code(acquirer.exchange(Message.of("0800").set(7, "1005120000").set(11, "000007")))).isEqualTo("30");
        }
    }

    @Test
    void aReversalIsForwardedAndTheIssuerReleasesItsHold() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            Message request = h.purchase(PAN, "25.50");
            acquirer.exchange(request);
            assertThat(h.issuer.holds()).isEqualTo(1);

            Message response = acquirer.exchange(Messages.reversalOf(request, h.nextStan(), "1005120500"));

            assertThat(response.mti()).isEqualTo("0410");
            assertThat(code(response)).isEqualTo("00");
            assertThat(h.issuer.holds()).isZero();
            assertThat(h.issuer.received("0400")).hasSize(1);
        }
    }

    @Test
    void aReversalForACardNobodyOwnsIsAnsweredOkBecauseThereIsNothingToRelease() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            Message request = h.purchase("4111111111111111", "5.00");
            assertThat(code(acquirer.exchange(Messages.reversalOf(request, h.nextStan(), "1005120500")))).isEqualTo("00");
            assertThat(h.issuer.received("0400")).isEmpty();
        }
    }

    @Test
    void aResendGetsTheIdenticalAnswerAndOneHold() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            Message request = h.purchase(PAN, "25.50");

            Message first = acquirer.exchange(request);
            Message second = acquirer.exchange(request);

            assertThat(second).isEqualTo(first);
            assertThat(h.issuer.holds()).isEqualTo(1);
        }
    }

    @Test
    void identicalRequestsAtTheSameMomentReachTheIssuerOnceAndAllGetTheSameAnswer() throws Exception {
        try (NetHarness h = new NetHarness()) {
            h.issuer.responseDelayMs = 150; // the first is still being decided when the others arrive
            Message request = h.purchase(PAN, "25.50");
            List<Callable<Message>> senders = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                senders.add(() -> {
                    try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                        return acquirer.exchange(request);
                    }
                });
            }

            List<Message> answers = raceAll(senders);

            assertThat(answers).allMatch(a -> a.equals(answers.get(0)));
            assertThat(code(answers.get(0))).isEqualTo("00");
            assertThat(h.issuer.received("0100")).hasSize(1);
            assertThat(h.issuer.holds()).isEqualTo(1);
        }
    }

    @Test
    void manyDifferentPaymentsAtOnceAllGetTheirOwnAnswer() throws Exception {
        try (NetHarness h = new NetHarness()) {
            List<Callable<Message>> senders = new ArrayList<>();
            List<Message> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Message request = h.purchase(i % 2 == 0 ? PAN : OTHER_PAN, "1.%02d".formatted(i));
                requests.add(request);
                senders.add(() -> {
                    try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                        return acquirer.exchange(request);
                    }
                });
            }

            List<Message> answers = raceAll(senders);

            for (int i = 0; i < 20; i++) {
                assertThat(answers.get(i).get(11)).isEqualTo(requests.get(i).get(11));
                assertThat(code(answers.get(i))).isEqualTo("00");
                assertThat(answers.get(i).get(4)).isEqualTo(requests.get(i).get(4));
            }
            assertThat(h.issuer.holds()).isEqualTo(20);
        }
    }

    @Test
    void hostileInputClosesThatConnectionOnlyAndTheNetworkKeepsWorking() throws Exception {
        try (NetHarness h = new NetHarness()) {
            byte[] valid = new com.bilal.cardnet.iso8583.Packager().pack(h.purchase(PAN, "5.00"));
            long before = h.network.server().closedForBadInput();
            List<byte[]> bad = new ArrayList<>();
            bad.add("HELLO WORLD, THIS IS NOT ISO 8583".getBytes(StandardCharsets.US_ASCII));
            bad.add(java.util.Arrays.copyOf(valid, valid.length - 1));
            bad.add(new byte[] { 0x00, 0x01, (byte) 0xFF });
            bad.add(new com.bilal.cardnet.iso8583.Packager().pack(Message.of("0300").set(11, "000001")));
            for (byte[] bytes : bad) {
                try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                    Frame.write(acquirer.out, bytes);
                    assertThat(acquirer.closedByNetwork()).isTrue();
                }
            }
            for (byte[] header : new byte[][] { { (byte) 0xFF, (byte) 0xFF }, { 0x00, 0x00 } }) {
                try (NetHarness.Acquirer acquirer = h.acquirer()) {
                    acquirer.out.write(header);
                    acquirer.out.flush();
                    assertThat(acquirer.closedByNetwork()).isTrue();
                }
            }

            assertThat(h.network.server().closedForBadInput() - before).isGreaterThanOrEqualTo(6);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00")))).isEqualTo("00");
            }
        }
    }

    @Test
    void theConnectionCapRefusesTheOneThatDoesNotFit() throws Exception {
        try (NetHarness h = new NetHarness(c -> c.maxConnections = 2);
                NetHarness.Acquirer first = h.acquirer().signedOn(); NetHarness.Acquirer second = h.acquirer().signedOn()) {
            try (NetHarness.Acquirer third = h.acquirer()) {
                assertThat(third.closedByNetwork()).isTrue();
            }
            assertThat(h.network.server().refusedOverCapacity()).isGreaterThanOrEqualTo(1);
            assertThat(code(first.exchange(h.purchase(PAN, "1.00")))).isEqualTo("00");
            assertThat(code(second.exchange(h.purchase(PAN, "1.00")))).isEqualTo("00");
        }
    }

    @Test
    void theIssuerLinkSignsOnAndKeepsTheLineAliveWithEchoes() throws Exception {
        try (NetHarness h = new NetHarness()) {
            com.bilal.cardnet.shared.testing.Await.until("a sign-on and echoes reached the issuer", () -> h.issuer.received("0800").size() >= 3);

            List<Message> management = h.issuer.received("0800");
            assertThat(management.get(0).get(70)).isEqualTo("001");
            assertThat(management.subList(1, management.size())).allMatch(m -> m.get(70).equals("301"));
        }
    }

    @Test
    void theLinkReconnectsOnItsOwnAfterTheIssuerRestarts() throws Exception {
        try (NetHarness h = new NetHarness()) {
            h.issuer.stop();
            h.waitForLinkDown();
            h.issuer.restart();
            h.waitForLink();

            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00")))).isEqualTo("00");
            }
            assertThat(h.issuer.received("0800").stream().filter(m -> "001".equals(m.get(70))).count()).as("signed on again").isEqualTo(2);
        }
    }

    @Test
    void anIssuerThatStopsAnsweringEchoesIsDeclaredDown() throws Exception {
        try (NetHarness h = new NetHarness()) {
            h.issuer.ignoreNetworkManagement = true;
            h.waitForLinkDown();
            h.issuer.ignoreNetworkManagement = false;
            h.waitForLink();
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
