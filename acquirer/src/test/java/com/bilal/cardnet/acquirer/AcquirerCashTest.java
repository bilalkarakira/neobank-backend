package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.acquirer.AcquirerService.BadRequestException;
import com.bilal.cardnet.acquirer.AcquirerService.ConflictException;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.PinBlock;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;

import static com.bilal.cardnet.acquirer.AcqHarness.PAN;
import static com.bilal.cardnet.acquirer.AcqHarness.PIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cash machine: a withdrawal is an authorization with processing code 010000, always with the PIN, cleared like a payment. */
class AcquirerCashTest {

    @TempDir
    Path root;

    private static AcquirerService.WithdrawRequest withdrawal(String amount) {
        return new AcquirerService.WithdrawRequest(PAN, "10/29", amount, PIN);
    }

    @Test
    void aWithdrawalIsAnAuthorizationWithTheCashCodeTheMachineCategoryAndThePin() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            AcquirerService.Result result = h.service().withdraw(withdrawal("100"));

            assertThat(result.status()).isEqualTo("APPROVED");
            Message sent = h.issuer.received("0100").get(0);
            assertThat(sent.get(3)).isEqualTo("010000");
            assertThat(sent.get(18)).isEqualTo("6011");
            assertThat(sent.get(4)).isEqualTo("000000010000");
            assertThat(sent.get(52)).as("the PIN block is always sent").isEqualTo(PinBlock.encode(PIN, PAN));
            assertThat(sent.get(42)).isEqualTo("ATM000000000001");
            assertThat(sent.get(43).strip()).isEqualTo("City ATM");
            assertThat(sent.get(32)).isEqualTo("123456");
            assertThat(sent.get(41)).isEqualTo("TERM0001");
            assertThat(h.scalar("select status || '/' || amount || '/' || merchant_name || '/' || mcc || '/' || pin_used from acq_transactions"))
                    .isEqualTo("APPROVED/100.00/City ATM/6011/true");
            assertThat(h.service().store().request(result.id()).has(52)).as("no PIN data is kept").isFalse();
            assertThat(h.service().store().request(result.id()).get(3)).isEqualTo("010000");
            assertThat(h.service().store().find(result.id()).orElseThrow().cash()).isTrue();
            assertThat(h.issuer.holds()).isEqualTo(1);
        }
    }

    @Test
    void theMachineRefusesWhatItCannotPayWithoutAskingTheBank() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            for (String bad : new String[] {"15", "5", "0", "10.50", "-10", "abc", "", "  ", "10.5", "505"}) {
                assertThatThrownBy(() -> h.service().withdraw(withdrawal(bad))).as("'" + bad + "'").isInstanceOf(BadRequestException.class);
            }
            assertThatThrownBy(() -> h.service().withdraw(withdrawal("510"))).isInstanceOf(BadRequestException.class).hasMessageContaining("at most 500");
            assertThatThrownBy(() -> h.service().withdraw(withdrawal(null))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> h.service().withdraw(new AcquirerService.WithdrawRequest(PAN, "10/29", "100", null)))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("needs the PIN");
            assertThatThrownBy(() -> h.service().withdraw(new AcquirerService.WithdrawRequest(PAN, "10/29", "100", "  ")))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("needs the PIN");
            assertThatThrownBy(() -> h.service().withdraw(new AcquirerService.WithdrawRequest(PAN, "10/29", "100", "12")))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> h.service().withdraw(new AcquirerService.WithdrawRequest("123", "10/29", "100", PIN))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> h.service().withdraw(new AcquirerService.WithdrawRequest(PAN, "1029", "100", PIN))).isInstanceOf(BadRequestException.class);

            assertThat(h.issuer.received("0100")).as("nothing reached the bank").isEmpty();
            assertThat(h.count("select count(*) from acq_transactions")).isZero();
        }
    }

    @Test
    void theBoundariesOfWhatTheMachinePaysAreAccepted() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            assertThat(h.service().withdraw(withdrawal("10")).status()).isEqualTo("APPROVED");
            assertThat(h.service().withdraw(withdrawal("500")).status()).isEqualTo("APPROVED");
            assertThat(h.issuer.received("0100")).extracting(m -> m.get(4)).containsExactly("000000001000", "000000050000");
        }
    }

    @Test
    void theBanksRefusalIsShownAndNothingIsHeld() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.issuer.decision = m -> "61";

            AcquirerService.Result result = h.service().withdraw(withdrawal("400"));

            assertThat(result.status()).isEqualTo("DECLINED");
            assertThat(result.responseCode()).isEqualTo("61");
            assertThat(result.message()).contains("Declined (61)");
            assertThat(h.issuer.holds()).isZero();
            assertThat(h.scalar("select status from acq_transactions")).isEqualTo("DECLINED");
        }
    }

    @Test
    void aWithdrawalClearsWithItsOwnCodeAndNeitherATipNorARefundIsPossible() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            UUID cash = h.service().withdraw(withdrawal("100")).id();
            UUID shop = h.payApproved("20.00");

            assertThatThrownBy(() -> h.acquirer.clearing().adjust(cash, "110.00")).isInstanceOf(ConflictException.class).hasMessageContaining("cash withdrawal cannot be changed");
            assertThat(h.acquirer.clearing().adjust(shop, "22.00").finalAmount()).as("a shop payment still takes a tip").isEqualByComparingTo("22.00");

            h.acquirer.clearing().closeDay().orElseThrow();
            h.pumpUntil("both cleared", () -> h.status(cash).equals("CLEARED") && h.status(shop).equals("CLEARED"));

            ClearingFile sent = h.clearingIssuer.received.get(0);
            Message cashRecord = sent.presentments().stream().filter(m -> m.get(18).equals("6011")).findFirst().orElseThrow();
            assertThat(cashRecord.get(3)).isEqualTo("010000");
            assertThat(cashRecord.get(4)).isEqualTo("000000010000");
            assertThat(cashRecord.get(38)).isNotBlank();
            assertThat(sent.presentments().stream().filter(m -> !m.get(18).equals("6011")).findFirst().orElseThrow().get(3)).isEqualTo("000000");
            assertThatThrownBy(() -> h.acquirer.clearing().refund(cash, "10.00")).isInstanceOf(ConflictException.class).hasMessageContaining("cannot be refunded");
            assertThat(h.acquirer.clearing().refund(shop, "5.00").kind()).as("a shop payment can still be refunded").isEqualTo("REFUND");
        }
    }

    @Test
    void aWithdrawalCanBeVoidedLikeAnyApprovedPayment() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            UUID cash = h.service().withdraw(withdrawal("50")).id();

            AcquirerService.Result voided = h.service().voidSale(cash);

            assertThat(voided.status()).isIn("REVERSING", "REVERSED");
            com.bilal.cardnet.shared.testing.Await.until("the reversal reaches the bank", () -> !h.issuer.received("0400").isEmpty());
            assertThat(h.issuer.received("0400")).hasSize(1);
            assertThat(h.issuer.received("0400").get(0).get(4)).isEqualTo("000000005000");
        }
    }

    @Test
    void theConsoleTakesAWithdrawalAndMarksItInTheList() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + h.acquirer.consolePort();

            HttpResponse<String> config = http.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(config.body()).contains("\"atm\":{\"name\":\"City ATM\",\"max\":\"500\"}");

            HttpResponse<String> ok = http.send(post(base + "/api/withdraw", "{\"pan\":\"" + PAN + "\",\"expiry\":\"10/29\",\"amount\":\"60\",\"pin\":\"" + PIN + "\"}"),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).contains("\"status\":\"APPROVED\"");

            HttpResponse<String> noPin = http.send(post(base + "/api/withdraw", "{\"pan\":\"" + PAN + "\",\"expiry\":\"10/29\",\"amount\":\"60\"}"), HttpResponse.BodyHandlers.ofString());
            assertThat(noPin.statusCode()).isEqualTo(400);
            assertThat(noPin.body()).contains("needs the PIN");
            HttpResponse<String> wrongMethod = http.send(HttpRequest.newBuilder(URI.create(base + "/api/withdraw")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(wrongMethod.statusCode()).isEqualTo(405);

            HttpResponse<String> list = http.send(HttpRequest.newBuilder(URI.create(base + "/api/transactions")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(list.body()).contains("\"cash\":true").contains("City ATM");
            assertThat(list.body()).doesNotContain(PAN);
        }
    }

    private static HttpRequest post(String url, String json) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }
}
