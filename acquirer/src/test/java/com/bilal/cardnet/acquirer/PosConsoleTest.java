package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.shared.testing.Await;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.bilal.cardnet.acquirer.AcqHarness.CAFE;
import static com.bilal.cardnet.acquirer.AcqHarness.PAN;
import static org.assertj.core.api.Assertions.assertThat;

/** The console's HTTP surface: the page, the JSON API, the proxy to the network, and what it never shows. */
class PosConsoleTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(AcqHarness h, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h.acquirer.consolePort() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(AcqHarness h, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h.acquirer.consolePort() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String payBody(String pan, String amount, String pin) {
        return "{\"pan\":\"" + pan + "\",\"expiry\":\"10/29\",\"amount\":\"" + amount + "\",\"merchantId\":\"" + CAFE + "\""
                + (pin == null ? "" : ",\"pin\":\"" + pin + "\"") + "}";
    }

    @Test
    void thePageIsServedAndSaysWhatItIs() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            HttpResponse<String> page = get(h, "/");

            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
            assertThat(page.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'self'");
            assertThat(page.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(page.body()).contains("Virtual POS").contains("not a full bank").contains("Take a payment").contains("Recent payments");
            assertThat(page.body()).as("the page holds no card number of its own").doesNotContain(PAN);
        }
    }

    @Test
    void theConfigurationListsTheShopsAndTheDemoCard() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            String body = get(h, "/api/config").body();

            assertThat(body).contains("Corner Cafe").contains("Casino Royale").contains("\"mcc\":\"7995\"").contains("\"expiry\":\"10/29\"")
                    .contains("\"acquirerId\":\"123456\"").contains("\"terminalId\":\"TERM0001\"");
        }
    }

    @Test
    void aPaymentThroughTheApiReturnsTheOutcomeAndOnlyMaskedAndRedactedMessages() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            HttpResponse<String> response = post(h, "/api/pay", payBody(PAN, "25.50", "4321"));

            assertThat(response.statusCode()).isEqualTo(200);
            String body = response.body();
            assertThat(body).contains("\"status\":\"APPROVED\"").contains("\"responseCode\":\"00\"").contains("\"authCode\":\"FK");
            assertThat(body).contains("999900******0012").contains("MTI 0100").contains("MTI 0110");
            assertThat(body).doesNotContain(PAN).doesNotContain("4321").doesNotContain(com.bilal.cardnet.iso8583.PinBlock.encode("4321", PAN));
            assertThat(body).as("the hex dumps").contains("requestHex").contains("responseHex");
        }
    }

    @Test
    void aDeclineThroughTheApiExplainsItself() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            h.issuer.decision = m -> "55";

            String body = post(h, "/api/pay", payBody(PAN, "25.50", "0000")).body();

            assertThat(body).contains("\"status\":\"DECLINED\"").contains("\"responseCode\":\"55\"").contains("Wrong PIN");
        }
    }

    @Test
    void theTransactionsListShowsMaskedCardsAndOffersWhatHappened() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            post(h, "/api/pay", payBody(PAN, "25.50", null));
            h.issuer.decision = m -> "51";
            post(h, "/api/pay", payBody(PAN, "9.00", null));

            String body = get(h, "/api/transactions").body();

            assertThat(body).contains("\"merchant\":\"Corner Cafe\"").contains("\"card\":\"999900******0012\"").contains("\"amount\":\"25.50\"")
                    .contains("\"status\":\"APPROVED\"").contains("\"status\":\"DECLINED\"").contains("Not enough available money");
            assertThat(body).doesNotContain(PAN);
            assertThat(body.indexOf("9.00")).as("newest first").isLessThan(body.indexOf("25.50"));
        }
    }

    @Test
    void voidAndResendWorkThroughTheApi() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            String paid = post(h, "/api/pay", payBody(PAN, "25.50", null)).body();
            String id = paid.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

            HttpResponse<String> resend = post(h, "/api/resend", "");
            assertThat(resend.statusCode()).isEqualTo(200);
            assertThat(resend.body()).contains("same answer again");

            HttpResponse<String> voided = post(h, "/api/void", "{\"id\":\"" + id + "\"}");
            assertThat(voided.statusCode()).isEqualTo(200);
            assertThat(voided.body()).contains("\"status\":\"REVERSING\"");
            Await.until("reversed", () -> h.status(java.util.UUID.fromString(id)).equals("REVERSED"));
            assertThat(h.issuer.holds()).isZero();

            assertThat(post(h, "/api/void", "{\"id\":\"" + id + "\"}").statusCode()).as("a second void").isEqualTo(409);
        }
    }

    @Test
    void badRequestsGetTheRightStatusAndAMessage() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            assertThat(post(h, "/api/pay", payBody("123", "5.00", null)).statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/pay", payBody(PAN, "-5", null)).statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/pay", "not json").statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/pay", "").statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/pay", "{\"pan\":{\"x\":1}}").statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/pay", "x".repeat(5000)).body()).contains("too large");
            assertThat(post(h, "/api/void", "{\"id\":\"nonsense\"}").statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/void", "{}").statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/void", "{\"id\":\"" + java.util.UUID.randomUUID() + "\"}").statusCode()).isEqualTo(404);
            assertThat(post(h, "/api/resend", "").statusCode()).as("nothing to resend yet").isEqualTo(404);
            assertThat(post(h, "/api/pay", payBody("123", "5.00", null)).body()).contains("\"error\"").contains("13 to 19 digits");
            assertThat(h.count("select count(*) from acq_transactions")).isZero();
        }
    }

    @Test
    void theWrongMethodAndUnknownPathsAreRefused() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            assertThat(get(h, "/api/pay").statusCode()).isEqualTo(405);
            assertThat(get(h, "/api/pay").headers().firstValue("Allow")).contains("POST");
            assertThat(post(h, "/api/transactions", "").statusCode()).isEqualTo(405);
            assertThat(post(h, "/", "").statusCode()).isEqualTo(405);
            assertThat(get(h, "/api/nothing").statusCode()).isEqualTo(404);
            assertThat(get(h, "/admin").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void theNetworkPanelIsProxiedIncludingTheChaosSwitches() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            assertThat(get(h, "/api/network").body()).contains("\"issuerLink\":\"UP\"").contains("\"counters\"");

            HttpResponse<String> set = post(h, "/api/network/chaos", "{\"acquirerResponseDelayMs\":250}");
            assertThat(set.statusCode()).isEqualTo(200);
            assertThat(h.network.chaos().acquirerResponseDelayMs()).isEqualTo(250);

            HttpResponse<String> bad = post(h, "/api/network/chaos", "{\"issuerResponseDelayMs\":-1}");
            assertThat(bad.statusCode()).as("the network's own refusal is passed on").isEqualTo(400);
            assertThat(bad.body()).contains("error");
        }
    }

    @Test
    void whenTheNetworkIsNotReachableThePanelSaysSo() throws Exception {
        try (AcqHarness h = new AcqHarness(c -> c.networkControlUrl = "http://127.0.0.1:1")) {
            HttpResponse<String> response = get(h, "/api/network");

            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(response.body()).contains("not reachable");
            assertThat(post(h, "/api/network/chaos", "{\"issuerDown\":true}").statusCode()).isEqualTo(502);
        }
    }

    @Test
    void theNetworkPanelShowsTheIssuerGoingDownAndComingBack() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            post(h, "/api/network/chaos", "{\"issuerDown\":true}");
            Await.until("the panel shows it down", () -> {
                try {
                    return get(h, "/api/network").body().contains("\"issuerLink\":\"DOWN\"");
                } catch (Exception e) {
                    return false;
                }
            });
            String body = post(h, "/api/pay", payBody(PAN, "25.50", null)).body();
            assertThat(body).contains("\"status\":\"APPROVED\"").contains("\"authCode\":\"SI");
            post(h, "/api/network/chaos", "{\"issuerDown\":false}");
            Await.until("and up again", () -> {
                try {
                    return get(h, "/api/network").body().contains("\"issuerLink\":\"UP\"");
                } catch (Exception e) {
                    return false;
                }
            });
        }
    }
}
