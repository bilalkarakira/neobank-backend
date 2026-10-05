package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.Messages;
import com.bilal.cardnet.shared.testing.Await;
import com.bilal.cardnet.shared.testing.FakeIssuer;
import org.junit.jupiter.api.Test;

import static com.bilal.cardnet.network.NetHarness.PAN;
import static com.bilal.cardnet.network.NetHarness.code;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The issuer is slow rather than dead. The issuer timeout is 500 ms here, and the fake issuer answers after 1,500 ms but has
 * DECIDED immediately, so what it holds at the end is what the shop's answer must agree with.
 */
class LateAnswerTest {

    private static void slowIssuer(NetHarness h) {
        h.issuer.responseDelayMs = 1500;
    }

    @Test
    void aSlowIssuerMeansTheNetworkAnswersAfterTheTimeout() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            slowIssuer(h);
            long started = System.nanoTime();

            Message response = acquirer.exchange(h.purchase(PAN, "25.50"));

            long millis = (System.nanoTime() - started) / 1_000_000;
            assertThat(code(response)).isEqualTo("00");
            assertThat(response.get(38)).startsWith("SI");
            assertThat(millis).as("after the 500 ms timeout but well before the issuer's 1,500 ms").isBetween(450L, 1_300L);
            assertThat(h.scalar("select route || '/' || note from net_transactions where mti = '0100' and late = false"))
                    .startsWith("STAND_IN/issuer timed out");
        }
    }

    @Test
    void aLateApprovalAfterAStandInApprovalIsKeptAndTheAdviceIsDropped() throws Exception {
        // delivery passes are far apart here, so the advice is still waiting when the issuer's late answer arrives
        try (NetHarness h = new NetHarness(c -> c.adviceInterval = java.time.Duration.ofSeconds(30)); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            slowIssuer(h);
            Message request = h.purchase(PAN, "25.50");
            assertThat(acquirer.exchange(request).get(38)).startsWith("SI"); // the shop heard yes

            Await.until("the late answer was handled", () -> h.count("select count(*) from net_standin_advices where status = 'CANCELLED'") == 1);

            assertThat(h.scalar("select cancel_reason from net_standin_advices")).isEqualTo("ISSUER_ANSWERED_LATE");
            assertThat(h.issuer.received("0120")).as("the issuer needs no advice: it approved on its own").isEmpty();
            assertThat(h.issuer.received("0400")).as("and nothing is reversed").isEmpty();
            assertThat(h.issuer.holds()).as("one hold, agreeing with what the shop was told").isEqualTo(1);
            assertThat(h.count("select count(*) from net_transactions where late = true")).isEqualTo(1);
            assertThat(h.network.router().lateCount()).isZero();
        }
    }

    @Test
    void ifTheAdviceWasDeliveredBeforeTheLateApprovalTheEndStateIsTheSame() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) { // the link is up, so the advice goes out at once
            slowIssuer(h);
            Message request = h.purchase(PAN, "25.50");
            assertThat(acquirer.exchange(request).get(38)).startsWith("SI");

            Await.until("the late answer was handled", () -> h.network.router().lateCount() == 0);
            Thread.sleep(300);

            assertThat(h.issuer.received("0400")).as("nothing is reversed").isEmpty();
            assertThat(h.issuer.holds()).as("exactly one hold, whichever way the race went").isEqualTo(1);
            assertThat(h.issuer.received("0120").size()).isLessThanOrEqualTo(1);
            assertThat(h.scalar("select status from net_standin_advices")).isIn("DELIVERED", "CANCELLED");
        }
    }

    @Test
    void aLateApprovalAfterTheShopWasToldNoIsReversedSoNoHoldIsLeftBehind() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            slowIssuer(h);
            Message request = h.purchase(PAN, "80.00"); // too big for a stand-in approval

            Message response = acquirer.exchange(request);

            assertThat(code(response)).as("the shop heard no").isEqualTo("91");
            Await.until("the issuer's hold was released again", () ->
                    h.issuer.received("0400").size() == 1 && h.issuer.holds() == 0);
            FakeIssuer.Entry entry = h.issuer.entryForStan(request.get(11));
            assertThat(entry.status()).isEqualTo(FakeIssuer.Status.REVERSED);
            Message reversal = h.issuer.received("0400").get(0);
            assertThat(Messages.original(reversal).stan()).isEqualTo(request.get(11));
            assertThat(reversal.get(4)).isEqualTo(request.get(4));
            Await.until("the reversal is marked done", () ->
                    h.count("select count(*) from pending_reversals where owner = 'NETWORK' and status = 'DONE'") == 1);
        }
    }

    @Test
    void aLateApprovalForAPaymentWithAPinIsReversedAndTheQueuedCopyHoldsNoPinData() throws Exception {
        try (NetHarness h = new NetHarness(c -> c.reversalPoll = java.time.Duration.ofSeconds(30)); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            slowIssuer(h);
            Message request = h.purchase(PAN, "5.00").set(52, "0123456789ABCDEF");

            assertThat(code(acquirer.exchange(request))).as("a PIN: no stand-in").isEqualTo("91");
            // the worker polls only every 30 s here and is woken at once; stop it from sending so the queued copy can be read
            Await.until("the reversal is queued", () -> h.count("select count(*) from pending_reversals where owner = 'NETWORK'") == 1);

            java.util.UUID id = java.util.UUID.fromString(h.scalar("select id::text from pending_reversals"));
            byte[] ciphertext = h.bytes("select request_ciphertext from pending_reversals");
            Message queued = new com.bilal.cardnet.shared.PendingReversalStore(h.ds, h.vault)
                    .original(new com.bilal.cardnet.shared.PendingReversalStore.Due(id, ciphertext, 0, null, null));
            assertThat(queued.get(2)).isEqualTo(PAN);
            assertThat(queued.has(52)).as("PIN data is not even kept encrypted").isFalse();
        }
    }

    @Test
    void aLateDeclineAfterAStandInApprovalLeavesTheAdviceInForceAndTheIssuerCarriesIt() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            slowIssuer(h);
            h.issuer.decision = m -> "51"; // the issuer, in its own time, says no
            Message request = h.purchase(PAN, "25.50");

            assertThat(acquirer.exchange(request).get(38)).startsWith("SI"); // the shop heard yes

            Await.until("the advice is delivered and overrides the decline", () ->
                    h.issuer.received("0120").size() >= 1 && h.issuer.holds() == 1);
            FakeIssuer.Entry entry = h.issuer.entryForStan(request.get(11));
            assertThat(entry.status()).isEqualTo(FakeIssuer.Status.AUTHORIZED);
            assertThat(entry.viaStandIn()).isTrue();
            assertThat(h.issuer.received("0400")).isEmpty();
        }
    }

    @Test
    void aDeclineThatArrivesLateForARefusedPaymentNeedsNothing() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            slowIssuer(h);
            h.issuer.decision = m -> "51";

            assertThat(code(acquirer.exchange(h.purchase(PAN, "80.00")))).isEqualTo("91");
            Await.until("the late answer was handled", () -> h.network.router().lateCount() == 0);
            Thread.sleep(300);

            assertThat(h.issuer.received("0400")).isEmpty();
            assertThat(h.issuer.holds()).isZero();
        }
    }

    @Test
    void anAnswerThatNobodyWasWaitingForAndNobodyRememberedIsIgnored() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            // answers of reversals and echoes that arrive when nobody waits must not disturb anything
            h.issuer.dropPaymentResponses = true;
            assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00")))).isEqualTo("00"); // stand-in after the timeout
            h.issuer.dropPaymentResponses = false;
            assertThat(h.network.router().lateCount()).as("no answer ever came, so the memory stays until it is old").isEqualTo(1);
            assertThat(h.network.link().state()).isEqualTo(IssuerLink.State.UP);
        }
    }

    @Test
    void theChaosDelayOfTheIssuerAnswersBehavesLikeASlowIssuer() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            h.network.chaos().issuerResponseDelayMs(1500); // the network holds the issuer's answers back
            Message request = h.purchase(PAN, "80.00");

            assertThat(code(acquirer.exchange(request))).isEqualTo("91");
            Await.until("late approval reversed", () -> h.issuer.received("0400").size() == 1 && h.issuer.holds() == 0);

            h.network.chaos().issuerResponseDelayMs(0);
            assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00")))).as("normal again").isEqualTo("00");
        }
    }

    @Test
    void theNetworkSlownessChaosHoldsBackItsOwnAnswers() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            h.network.chaos().acquirerResponseDelayMs(400);
            long started = System.nanoTime();

            Message response = acquirer.exchange(h.purchase(PAN, "5.00"));

            assertThat(code(response)).isEqualTo("00");
            assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(380);
            long echoStarted = System.nanoTime();
            acquirer.exchange(Messages.echo("000042", "1005120000"));
            assertThat((System.nanoTime() - echoStarted) / 1_000_000).as("network management is not slowed").isLessThan(300);
        }
    }

    @Test
    void aLateReversalOfTheNetworkIsRetriedUntilTheIssuerAcknowledgesIt() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            slowIssuer(h);
            h.issuer.dropReversalAnswers = 2; // the issuer applies the reversal but its answer is lost, twice
            Message request = h.purchase(PAN, "80.00");

            assertThat(code(acquirer.exchange(request))).isEqualTo("91");

            Await.until("acknowledged on the third try", () ->
                    h.count("select count(*) from pending_reversals where owner = 'NETWORK' and status = 'DONE'") == 1);
            assertThat(h.issuer.received("0400").size()).isEqualTo(3);
            assertThat(h.issuer.holds()).isZero();
            assertThat(h.scalar("select attempts::text from pending_reversals")).isEqualTo("2");
        }
    }
}
