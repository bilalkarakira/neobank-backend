package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.Messages;
import com.bilal.cardnet.shared.testing.Await;
import com.bilal.cardnet.shared.testing.FakeIssuer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.bilal.cardnet.network.NetHarness.OTHER_PAN;
import static com.bilal.cardnet.network.NetHarness.PAN;
import static com.bilal.cardnet.network.NetHarness.code;
import static org.assertj.core.api.Assertions.assertThat;

/** The issuer is down: the network answers for it within strict limits, then tells it afterwards. */
class StandInFlowTest {

    private static void issuerDown(NetHarness h) {
        h.issuer.stop();
        h.waitForLinkDown();
    }

    private static void issuerBack(NetHarness h) {
        h.issuer.restart();
        h.waitForLink();
    }

    // ---- while the issuer is down ----

    @Test
    void aSmallPurchaseIsApprovedByTheNetworkWithItsOwnCodeAndRemembered() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                Message request = h.purchase(PAN, "25.50");

                Message response = acquirer.exchange(request);

                assertThat(response.mti()).isEqualTo("0110");
                assertThat(code(response)).isEqualTo("00");
                assertThat(response.get(38)).matches("SI[A-Z0-9]{4}");
                for (int field : Messages.ECHOED) {
                    assertThat(response.get(field)).as("DE " + field).isEqualTo(request.get(field));
                }
                assertThat(h.scalar("select status || '/' || amount || '/' || auth_code from net_standin_advices"))
                        .isEqualTo("PENDING/25.50/" + response.get(38));
                assertThat(h.scalar("select route || '/' || answer_code from net_transactions where mti = '0100'")).isEqualTo("STAND_IN/00");
            }
        }
    }

    @Test
    void everyStandInRuleRefusesWith91AndRemembersNothing() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                assertThat(code(acquirer.exchange(h.purchase(PAN, "50.01")))).as("over the limit").isEqualTo("91");
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00").set(52, "0123456789ABCDEF")))).as("a PIN was sent").isEqualTo("91");
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00").set(18, "7995")))).as("blocked merchant").isEqualTo("91");
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00").set(3, "010000")))).as("cash").isEqualTo("91");
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00").set(49, "978")))).as("euro").isEqualTo("91");
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00").set(90, "0100000001" + "1005120000" + "00000123456" + "00000000000")))).as("an increment").isEqualTo("91");
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00").set(22, "812").set(25, "59").set(53, "123")))).as("an online payment").isEqualTo("91");
            }
            assertThat(h.count("select count(*) from net_standin_advices")).isZero();
            assertThat(h.count("select count(*) from net_transactions where route = 'REFUSED' and answer_code = '91'")).isEqualTo(7);
        }
    }

    @Test
    void theDailyTotalOfOneCardIsEnforcedExactlyAndOtherCardsHaveTheirOwn() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                assertThat(code(acquirer.exchange(h.purchase(PAN, "50.00")))).isEqualTo("00");
                assertThat(code(acquirer.exchange(h.purchase(PAN, "50.00")))).as("exactly the cap of 100.00").isEqualTo("00");
                assertThat(code(acquirer.exchange(h.purchase(PAN, "0.01")))).as("one cent over").isEqualTo("91");
                assertThat(code(acquirer.exchange(h.purchase(OTHER_PAN, "50.00")))).as("another card").isEqualTo("00");
            }
        }
    }

    @Test
    void aRefusedOrWithdrawnStandInDoesNotUseUpTheDailyTotal() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                Message first = h.purchase(PAN, "50.00");
                assertThat(code(acquirer.exchange(first))).isEqualTo("00");
                assertThat(code(acquirer.exchange(h.purchase(PAN, "50.00")))).isEqualTo("00");
                assertThat(code(acquirer.exchange(h.purchase(PAN, "10.00")))).as("the cap is reached").isEqualTo("91");

                assertThat(code(acquirer.exchange(Messages.reversalOf(first, h.nextStan(), "1005120500")))).isEqualTo("00");

                assertThat(code(acquirer.exchange(h.purchase(PAN, "50.00")))).as("the withdrawn 50.00 is available again").isEqualTo("00");
            }
        }
    }

    @Test
    void aResendWhileTheIssuerIsDownGetsTheSameCodeAndCountsOnce() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                Message request = h.purchase(PAN, "50.00");

                Message first = acquirer.exchange(request);
                Message second = acquirer.exchange(request);

                assertThat(second).isEqualTo(first);
                assertThat(h.count("select count(*) from net_standin_advices")).isEqualTo(1);
                assertThat(code(acquirer.exchange(h.purchase(PAN, "50.00")))).as("the resend did not eat into the cap").isEqualTo("00");
            }
        }
    }

    @Test
    void aResendOfAnApprovedRequestIsAnsweredAsBeforeEvenThoughItsOwnAmountAlreadyUsesUpTheCap() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                Message first = h.purchase(PAN, "50.00");
                Message second = h.purchase(PAN, "50.00");
                Message answer = acquirer.exchange(second);
                acquirer.exchange(first);
                assertThat(code(acquirer.exchange(h.purchase(PAN, "0.01")))).as("the cap of 100.00 is used up").isEqualTo("91");

                Message again = acquirer.exchange(second); // checked as a NEW request this would be 50 + 100 over the cap

                assertThat(again).isEqualTo(answer);
                assertThat(code(again)).isEqualTo("00");
            }
        }
    }

    @Test
    void aReversalWhileTheIssuerIsDownForAnOrdinaryPaymentIsAnsweredUnavailable() throws Exception {
        try (NetHarness h = new NetHarness()) {
            Message request = h.purchase(PAN, "25.50");
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                acquirer.exchange(request); // approved by the issuer itself
                issuerDown(h);

                Message response = acquirer.exchange(Messages.reversalOf(request, h.nextStan(), "1005120500"));

                assertThat(response.mti()).isEqualTo("0410");
                assertThat(code(response)).as("the acquirer owns the retries").isEqualTo("91");
            }
        }
    }

    @Test
    void theChaosSwitchHasTheSameEffectAsARealOutage() throws Exception {
        try (NetHarness h = new NetHarness()) {
            h.network.chaos().issuerDown(true);
            h.waitForLinkDown();
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00")))).isEqualTo("00");
                assertThat(h.issuer.received("0100")).as("the issuer was never asked").isEmpty();
            }
            h.network.chaos().issuerDown(false);
            h.waitForLink();
            Await.until("the advice reaches the issuer", () -> h.issuer.received("0120").size() == 1);
        }
    }

    // ---- when the issuer comes back ----

    @Test
    void theAdviceIsDeliveredOnceTheIssuerIsBackAndBecomesAHoldMarkedStandIn() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            String authCode;
            Message request = h.purchase(PAN, "25.50");
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                authCode = acquirer.exchange(request).get(38);
            }
            assertThat(h.issuer.holds()).isZero();

            issuerBack(h);
            Await.until("the advice is delivered", () -> h.count("select count(*) from net_standin_advices where status = 'DELIVERED'") == 1);

            assertThat(h.issuer.received("0120")).hasSize(1);
            Message advice = h.issuer.received("0120").get(0);
            assertThat(advice.get(38)).isEqualTo(authCode);
            assertThat(advice.get(39)).isEqualTo("00");
            assertThat(advice.has(52)).isFalse();
            for (int field : new int[] { 2, 3, 4, 7, 11, 13, 14, 18, 32, 37, 41, 42, 49 }) {
                assertThat(advice.get(field)).as("DE " + field).isEqualTo(request.get(field));
            }
            FakeIssuer.Entry entry = h.issuer.entryForStan(request.get(11));
            assertThat(entry.status()).isEqualTo(FakeIssuer.Status.AUTHORIZED);
            assertThat(entry.viaStandIn()).isTrue();
            assertThat(entry.authCode()).isEqualTo(authCode);
            assertThat(h.issuer.holds()).isEqualTo(1);
        }
    }

    @Test
    void theAdviceIsDeliveredExactlyOnceNoMatterHowLongWeWait() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                acquirer.exchange(h.purchase(PAN, "25.50"));
            }
            issuerBack(h);
            Await.until("delivered", () -> h.count("select count(*) from net_standin_advices where status = 'DELIVERED'") == 1);

            Thread.sleep(600); // many delivery passes (every 80 ms) and several echoes
            h.network.delivery().deliverPending();

            assertThat(h.issuer.received("0120")).hasSize(1);
        }
    }

    @Test
    void severalAdvicesAreDeliveredOldestFirst() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            Message a = h.purchase(PAN, "1.00");
            Message b = h.purchase(PAN, "2.00");
            Message c = h.purchase(OTHER_PAN, "3.00");
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                for (Message m : new Message[] { a, b, c }) {
                    assertThat(code(acquirer.exchange(m))).isEqualTo("00");
                    Thread.sleep(5); // distinct creation times
                }
            }

            issuerBack(h);
            Await.until("all three delivered", () -> h.issuer.received("0120").size() == 3);

            assertThat(h.issuer.received("0120").stream().map(m -> m.get(11)).toList())
                    .containsExactly(a.get(11), b.get(11), c.get(11));
            assertThat(h.issuer.holds()).isEqualTo(3);
        }
    }

    @Test
    void anAdviceThatIsNotAcknowledgedIsTriedAgain() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                acquirer.exchange(h.purchase(PAN, "25.50"));
            }
            h.issuer.dropAdviceAnswers = true; // the issuer records it but its answer never arrives
            issuerBack(h);
            Await.until("a first attempt was made", () -> h.issuer.received("0120").size() >= 1);
            assertThat(h.count("select count(*) from net_standin_advices where status = 'DELIVERED'")).isZero();

            h.issuer.dropAdviceAnswers = false;
            Await.until("delivered at last", () -> h.count("select count(*) from net_standin_advices where status = 'DELIVERED'") == 1);

            assertThat(h.issuer.received("0120").size()).as("the repeat was harmless").isGreaterThanOrEqualTo(2);
            assertThat(h.issuer.holds()).as("still one hold, not two").isEqualTo(1);
        }
    }

    @Test
    void anAdviceSurvivesARestartOfTheNetwork() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                acquirer.exchange(h.purchase(PAN, "25.50"));
            }
            h.restartNetwork(); // the whole process "dies" while the issuer is still down
            assertThat(h.count("select count(*) from net_standin_advices where status = 'PENDING'")).isEqualTo(1);

            issuerBack(h);
            Await.until("delivered by the new process", () -> h.count("select count(*) from net_standin_advices where status = 'DELIVERED'") == 1);

            assertThat(h.issuer.received("0120")).hasSize(1);
            assertThat(h.issuer.holds()).isEqualTo(1);
        }
    }

    @Test
    void anAdviceThatWasBeingSentWhenTheProcessDiedGoesBackToPending() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                acquirer.exchange(h.purchase(PAN, "25.50"));
            }
            h.network.close();
            h.scalar("update net_standin_advices set status = 'SENDING' returning 1");

            h.network = Network.start(h.config, h.ds, h.vault, java.time.Clock.systemUTC());
            assertThat(h.count("select count(*) from net_standin_advices where status = 'PENDING'")).isEqualTo(1);
            issuerBack(h);
            Await.until("delivered", () -> h.count("select count(*) from net_standin_advices where status = 'DELIVERED'") == 1);
        }
    }

    // ---- reversals of stand-in approvals ----

    @Test
    void aReversalBeforeTheAdviceWithdrawsItAndTheIssuerNeverHearsOfThePayment() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            Message request = h.purchase(PAN, "25.50");
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                acquirer.exchange(request);

                Message response = acquirer.exchange(Messages.reversalOf(request, h.nextStan(), "1005120500"));

                assertThat(response.mti()).isEqualTo("0410");
                assertThat(code(response)).isEqualTo("00");
            }
            assertThat(h.scalar("select status || '/' || cancel_reason from net_standin_advices")).isEqualTo("CANCELLED/REVERSED_BEFORE_ADVICE");

            issuerBack(h);
            Thread.sleep(500);

            assertThat(h.issuer.received("0120")).as("no advice is ever sent").isEmpty();
            assertThat(h.issuer.received("0400")).as("and no reversal either: the issuer never knew").isEmpty();
            assertThat(h.issuer.holds()).isZero();
        }
    }

    @Test
    void aReversalAfterTheAdviceWasDeliveredIsForwardedAndTheHoldIsReleased() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            Message request = h.purchase(PAN, "25.50");
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                acquirer.exchange(request);
                issuerBack(h);
                Await.until("delivered", () -> h.count("select count(*) from net_standin_advices where status = 'DELIVERED'") == 1);
                assertThat(h.issuer.holds()).isEqualTo(1);

                Message response = acquirer.exchange(Messages.reversalOf(request, h.nextStan(), "1005120500"));

                assertThat(code(response)).isEqualTo("00");
            }
            assertThat(h.issuer.holds()).isZero();
            assertThat(h.issuer.entryForStan(request.get(11)).status()).isEqualTo(FakeIssuer.Status.REVERSED);
        }
    }

    @Test
    void whenTheIssuerIsBackTheNetworkAsksItAgainForNewPayments() throws Exception {
        try (NetHarness h = new NetHarness()) {
            issuerDown(h);
            issuerBack(h);
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                Message response = acquirer.exchange(h.purchase(PAN, "5.00"));
                assertThat(response.get(38)).startsWith("FK");
            }
        }
    }

    @Test
    void anIssuerThatSays91Or96IsTreatedAsUnavailable() throws Exception {
        try (NetHarness h = new NetHarness()) {
            h.issuer.decision = m -> "91";
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                Message response = acquirer.exchange(h.purchase(PAN, "5.00"));
                assertThat(code(response)).isEqualTo("00");
                assertThat(response.get(38)).startsWith("SI");

                h.issuer.decision = m -> "96";
                assertThat(acquirer.exchange(h.purchase(PAN, "5.00")).get(38)).startsWith("SI");

                h.issuer.decision = m -> "05";
                assertThat(code(acquirer.exchange(h.purchase(PAN, "5.00")))).as("05 is a real decision").isEqualTo("05");
            }
        }
    }
}
