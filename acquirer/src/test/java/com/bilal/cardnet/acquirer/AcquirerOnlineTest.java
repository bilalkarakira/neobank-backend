package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.acquirer.AcquirerService.BadRequestException;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.PendingReversalStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static com.bilal.cardnet.acquirer.AcqHarness.CAFE;
import static com.bilal.cardnet.acquirer.AcqHarness.PAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The online shop: a payment without a card in a terminal and without a PIN; the security code on the card goes in DE 53 and is never kept. */
class AcquirerOnlineTest {

    private static final String CVV = "731";

    private static AcquirerService.OnlineRequest online(String amount, String cvv) {
        return new AcquirerService.OnlineRequest(PAN, "10/29", cvv, amount, CAFE);
    }

    @Test
    void anOnlinePaymentCarriesTheOnlineEntryModeAndTheSecurityCodeAndNoPin() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result result = h.service().payOnline(online("19.99", CVV));

            assertThat(result.status()).isEqualTo("APPROVED");
            Message sent = h.issuer.received("0100").get(0);
            assertThat(sent.get(22)).isEqualTo("812");
            assertThat(sent.get(25)).isEqualTo("59");
            assertThat(sent.get(53)).isEqualTo(CVV);
            assertThat(sent.has(52)).as("no PIN online").isFalse();
            assertThat(sent.get(3)).isEqualTo("000000");
            assertThat(sent.get(4)).isEqualTo("000000001999");
            assertThat(sent.get(42)).isEqualTo(CAFE);
            assertThat(h.scalar("select status || '/' || online || '/' || pin_used from acq_transactions")).isEqualTo("APPROVED/true/false");
            assertThat(h.service().store().find(result.id()).orElseThrow().online()).isTrue();
        }
    }

    @Test
    void aPaymentInATerminalIsNotMarkedOnline() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID id = h.payApproved("12.00");

            assertThat(h.service().store().find(id).orElseThrow().online()).isFalse();
            assertThat(h.issuer.received("0100").get(0).has(53)).isFalse();
            assertThat(h.issuer.received("0100").get(0).get(22)).isEqualTo("051");
        }
    }

    @Test
    void theSecurityCodeIsNeverKeptNotInThePaymentNorInTheIncrementNorInAQueuedReversal() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.network.chaos().acquirerResponseDelayMs(1800);
            AcquirerService.Result result = h.service().payOnline(online("25.50", CVV));
            assertThat(result.status()).isEqualTo("REVERSING");
            assertThat(h.issuer.received("0100").get(0).has(53)).as("the payment itself carried it").isTrue();

            assertThat(h.service().store().request(result.id()).has(53)).as("the stored payment").isFalse();
            assertThat(h.service().store().request(result.id()).get(2)).isEqualTo(PAN);

            UUID id = UUID.fromString(h.scalar("select id::text from pending_reversals"));
            byte[] ciphertext;
            try (var c = h.ds.getConnection(); var ps = c.prepareStatement("select request_ciphertext from pending_reversals"); var rs = ps.executeQuery()) {
                rs.next();
                ciphertext = rs.getBytes(1);
            }
            Message queued = new PendingReversalStore(h.ds, h.vault).original(new PendingReversalStore.Due(id, ciphertext, 0, null, null));
            assertThat(queued.has(53)).as("the queued reversal").isFalse();
            assertThat(queued.get(22)).isEqualTo("812");
        }
    }

    @Test
    void theStoreKeepsNoSecurityCodeOrPinEvenIfAskedTo() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID paymentId = h.payApproved("20.00");
            UUID directId = UUID.randomUUID();
            Message direct = h.issuer.received("0100").get(0).copyWithMti("0100").set(11, "000778").set(37, "000000000778").set(53, CVV).set(52, "0123456789ABCDEF");
            h.service().store().insert(directId, direct, "Corner Cafe", true, java.time.Instant.now());
            assertThat(h.service().store().request(directId).has(53)).as("a payment record").isFalse();
            assertThat(h.service().store().request(directId).has(52)).isFalse();

            Message addition = h.issuer.received("0100").get(0).copyWithMti("0100").set(11, "000777").set(37, "000000000777").set(53, CVV).set(52, "0123456789ABCDEF");

            UUID incrementId = UUID.randomUUID();
            h.service().store().insertIncrement(incrementId, paymentId, addition, java.time.Instant.now());

            Message kept = h.service().store().incrementRequest(incrementId);
            assertThat(kept.has(53)).isFalse();
            assertThat(kept.has(52)).isFalse();
            assertThat(kept.get(11)).as("the rest is kept").isEqualTo("000777");
        }
    }

    @Test
    void whatTheConsoleShowsHidesTheCodeByItsPlace() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result result = h.service().payOnline(online("19.99", CVV));

            assertThat(result.requestText()).contains("Card verification value 2").doesNotContain("731");
            assertThat(result.requestHex()).doesNotContain(CVV);
            assertThat(result.responseHex()).doesNotContain(CVV);
            assertThat(result.requestText()).as("the amount and the rest are still shown").contains("000000001999").contains("812");
        }
    }

    @Test
    void whatIsNotAValidOnlinePaymentIsRefusedBeforeAnythingIsSent() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            for (String bad : new String[] {null, "", "  ", "12", "12345", "12a", "1 2", "abc"}) {
                assertThatThrownBy(() -> h.service().payOnline(online("10.00", bad))).as("code '" + bad + "'").isInstanceOf(BadRequestException.class)
                        .hasMessageContaining("security code");
            }
            for (String badAmount : new String[] {null, "", "0", "-5", "1.234", "x"}) {
                assertThatThrownBy(() -> h.service().payOnline(online(badAmount, CVV))).as("amount '" + badAmount + "'").isInstanceOf(BadRequestException.class);
            }
            assertThatThrownBy(() -> h.service().payOnline(new AcquirerService.OnlineRequest("123", "10/29", CVV, "5.00", CAFE))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> h.service().payOnline(new AcquirerService.OnlineRequest(PAN, "1029", CVV, "5.00", CAFE))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> h.service().payOnline(new AcquirerService.OnlineRequest(PAN, "10/29", CVV, "5.00", "NOPE"))).isInstanceOf(BadRequestException.class);

            assertThat(h.issuer.received("0100")).isEmpty();
            assertThat(h.count("select count(*) from acq_transactions")).isZero();
        }
    }

    @Test
    void aFourDigitCodeIsAccepted() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            assertThat(h.service().payOnline(online("5.00", "0042")).status()).isEqualTo("APPROVED");
            assertThat(h.issuer.received("0100").get(0).get(53)).isEqualTo("0042");
        }
    }

    @Test
    void aWrongSecurityCodeIsShownAsWhatItIs() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.issuer.decision = m -> "N7";

            AcquirerService.Result result = h.service().payOnline(online("19.99", CVV));

            assertThat(result.status()).isEqualTo("DECLINED");
            assertThat(result.responseCode()).isEqualTo("N7");
            assertThat(result.message()).contains("The security code is wrong.");
            assertThat(h.issuer.holds()).isZero();
        }
    }

    // ---- the customer's approval ----

    @Test
    void theBanksRequestForApprovalReachesTheShopUnchangedWithItsReference() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.issuer.decision = m -> "1A";
            h.issuer.challenge = m -> "3DS|K7M2QX";

            AcquirerService.Result result = h.service().payOnline(online("80.00", CVV));

            assertThat(result.status()).isEqualTo("DECLINED");
            assertThat(result.responseCode()).isEqualTo("1A");
            assertThat(result.challenge()).isEqualTo("K7M2QX");
            assertThat(result.message()).contains("must approve").contains("K7M2QX").contains("pay again");
            assertThat(result.responseText()).contains("3DS|K7M2QX");
            assertThat(h.scalar("select status || '/' || response_code from acq_transactions")).isEqualTo("DECLINED/1A");
            assertThat(h.issuer.holds()).isZero();
        }
    }

    @Test
    void aDeclineWithoutAChallengeShowsNoReference() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.issuer.decision = m -> "05";
            h.issuer.challenge = m -> "HELLO";

            AcquirerService.Result result = h.service().payOnline(online("80.00", CVV));

            assertThat(result.challenge()).as("DE 48 that is not a reference").isNull();
            assertThat(result.message()).contains("Declined (05)").doesNotContain("pay again");
        }
    }

    @Test
    void thePaymentTriedAgainCarriesTheReferenceInDe48AsANewPayment() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result first = h.service().payOnline(online("80.00", CVV));
            AcquirerService.Result again = h.service().payOnline(new AcquirerService.OnlineRequest(PAN, "10/29", CVV, "80.00", CAFE, "K7M2QX"));

            Message sentFirst = h.issuer.received("0100").get(0);
            Message sentAgain = h.issuer.received("0100").get(1);
            assertThat(sentFirst.has(48)).isFalse();
            assertThat(sentAgain.get(48)).isEqualTo("3DS|K7M2QX");
            assertThat(sentAgain.get(11)).as("its own trace number").isNotEqualTo(sentFirst.get(11));
            assertThat(sentAgain.get(37)).as("and retrieval reference").isNotEqualTo(sentFirst.get(37));
            assertThat(sentAgain.get(53)).isEqualTo(CVV);
            assertThat(again.id()).isNotEqualTo(first.id());
            assertThat(h.service().store().request(again.id()).get(48)).as("the reference is no secret, it is kept").isEqualTo("3DS|K7M2QX");
            assertThat(h.service().store().request(again.id()).has(53)).isFalse();
        }
    }

    @Test
    void aMalformedApprovalReferenceIsRefusedBeforeAnythingIsSent() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            for (String bad : new String[] {"abc", "TOOLONG123", "AB CD1", "AB-CD1", "3DS|ABC123", "ÄÖÜ123"}) {
                assertThatThrownBy(() -> h.service().payOnline(new AcquirerService.OnlineRequest(PAN, "10/29", CVV, "80.00", CAFE, bad)))
                        .as("'" + bad + "'").isInstanceOf(BadRequestException.class).hasMessageContaining("approval reference");
            }
            assertThat(h.issuer.received("0100")).isEmpty();
            AcquirerService.Result blank = h.service().payOnline(new AcquirerService.OnlineRequest(PAN, "10/29", CVV, "5.00", CAFE, "  "));
            assertThat(blank.status()).as("blank means none").isEqualTo("APPROVED");
            assertThat(h.issuer.received("0100").get(0).has(48)).isFalse();
        }
    }

    @Test
    void anOnlinePaymentCannotBeAddedTo() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID id = h.service().payOnline(online("20.00", CVV)).id();

            assertThatThrownBy(() -> h.service().increase(new AcquirerService.IncreaseRequest(id, "5.00")))
                    .isInstanceOf(AcquirerService.ConflictException.class).hasMessageContaining("online payment cannot be added to");
            assertThat(h.issuer.received("0100")).as("nothing was sent").hasSize(1);
        }
    }

    @Test
    void theConsoleCarriesTheReferenceBothWays() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.issuer.decision = m -> m.has(48) ? "00" : "1A";
            h.issuer.challenge = m -> "3DS|K7M2QX";
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + h.acquirer.consolePort();
            String body = "{\"pan\":\"" + PAN + "\",\"expiry\":\"10/29\",\"cvv\":\"" + CVV + "\",\"amount\":\"80.00\",\"merchantId\":\"" + CAFE + "\"%s}";

            HttpResponse<String> first = http.send(post(base + "/api/pay-online", body.formatted("")), HttpResponse.BodyHandlers.ofString());
            assertThat(first.body()).contains("\"responseCode\":\"1A\"").contains("\"challenge\":\"K7M2QX\"").doesNotContain(CVV);

            HttpResponse<String> retry = http.send(post(base + "/api/pay-online", body.formatted(",\"authentication\":\"K7M2QX\"")), HttpResponse.BodyHandlers.ofString());
            assertThat(retry.body()).contains("\"status\":\"APPROVED\"").contains("\"challenge\":null");

            HttpResponse<String> bad = http.send(post(base + "/api/pay-online", body.formatted(",\"authentication\":\"no\"")), HttpResponse.BodyHandlers.ofString());
            assertThat(bad.statusCode()).isEqualTo(400);
        }
    }

    @Test
    void theConsoleTakesAnOnlinePaymentAndNeverEchoesTheCode() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + h.acquirer.consolePort();

            HttpResponse<String> config = http.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(config.body()).contains("\"cvv\":\"123\"");

            HttpResponse<String> ok = http.send(post(base + "/api/pay-online", "{\"pan\":\"" + PAN + "\",\"expiry\":\"10/29\",\"cvv\":\"" + CVV + "\",\"amount\":\"30.00\","
                    + "\"merchantId\":\"" + CAFE + "\"}"), HttpResponse.BodyHandlers.ofString());
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).contains("\"status\":\"APPROVED\"");
            assertThat(ok.body()).doesNotContain(CVV);

            HttpResponse<String> noCode = http.send(post(base + "/api/pay-online", "{\"pan\":\"" + PAN + "\",\"expiry\":\"10/29\",\"amount\":\"30.00\",\"merchantId\":\""
                    + CAFE + "\"}"), HttpResponse.BodyHandlers.ofString());
            assertThat(noCode.statusCode()).isEqualTo(400);
            assertThat(noCode.body()).contains("security code");
            HttpResponse<String> wrongMethod = http.send(HttpRequest.newBuilder(URI.create(base + "/api/pay-online")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(wrongMethod.statusCode()).isEqualTo(405);

            HttpResponse<String> list = http.send(HttpRequest.newBuilder(URI.create(base + "/api/transactions")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(list.body()).contains("\"online\":true").doesNotContain(CVV).doesNotContain(PAN);
        }
    }

    private static HttpRequest post(String url, String json) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }
}
