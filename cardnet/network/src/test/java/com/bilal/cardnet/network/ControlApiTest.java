package com.bilal.cardnet.network;

import com.bilal.cardnet.shared.testing.Await;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.bilal.cardnet.network.NetHarness.PAN;
import static org.assertj.core.api.Assertions.assertThat;

/** The loopback control API: status, and the chaos switches. */
class ControlApiTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(NetHarness h, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h.network.controlPort() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(NetHarness h, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h.network.controlPort() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void statusShowsTheLinkTheCountersAndTheChaosSettings() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            acquirer.exchange(h.purchase(PAN, "5.00"));

            HttpResponse<String> status = get(h, "/status");

            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(status.headers().firstValue("Content-Type")).contains("application/json");
            assertThat(status.body()).contains("\"issuerLink\":\"UP\"").contains("\"advicesWaiting\":0").contains("\"reversalsWaiting\":0")
                    .contains("\"answeredByIssuer\":1").contains("\"issuerDown\":false").contains("\"issuerResponseDelayMs\":0");
        }
    }

    @Test
    void theIssuerCanBeSwitchedOffAndOnThroughTheApi() throws Exception {
        try (NetHarness h = new NetHarness()) {
            HttpResponse<String> off = post(h, "/chaos", "{\"issuerDown\":true}");
            assertThat(off.statusCode()).isEqualTo(200);
            assertThat(off.body()).contains("\"issuerDown\":true");
            h.waitForLinkDown();
            assertThat(get(h, "/status").body()).contains("\"issuerLink\":\"DOWN\"");

            post(h, "/chaos", "{\"issuerDown\":false}");
            h.waitForLink();
            assertThat(get(h, "/status").body()).contains("\"issuerLink\":\"UP\"");
        }
    }

    @Test
    void delaysAreSetAndReportedBack() throws Exception {
        try (NetHarness h = new NetHarness()) {
            HttpResponse<String> response = post(h, "/chaos", "{\"issuerResponseDelayMs\": 4000, \"acquirerResponseDelayMs\": 250}");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(h.network.chaos().issuerResponseDelayMs()).isEqualTo(4000);
            assertThat(h.network.chaos().acquirerResponseDelayMs()).isEqualTo(250);
            assertThat(get(h, "/status").body()).contains("\"issuerResponseDelayMs\":4000").contains("\"acquirerResponseDelayMs\":250");
        }
    }

    @Test
    void badRequestsAreRefusedWithAMessageAndChangeNothing() throws Exception {
        try (NetHarness h = new NetHarness()) {
            for (String bad : new String[] { "", "not json", "[]", "{\"issuerDown\":\"yes\"}", "{\"issuerResponseDelayMs\":-1}",
                    "{\"issuerResponseDelayMs\":99999}", "{\"bogus\":1}", "{\"issuerDown\":{\"x\":1}}", "x".repeat(5000) }) {
                HttpResponse<String> response = post(h, "/chaos", bad);
                assertThat(response.statusCode()).as(bad.substring(0, Math.min(30, bad.length()))).isEqualTo(400);
                assertThat(response.body()).contains("\"error\"");
            }
            assertThat(h.network.chaos().issuerDown()).isFalse();
            assertThat(h.network.chaos().issuerResponseDelayMs()).isZero();
        }
    }

    @Test
    void theWrongMethodIsRefused() throws Exception {
        try (NetHarness h = new NetHarness()) {
            assertThat(get(h, "/chaos").statusCode()).isEqualTo(405);
            assertThat(post(h, "/status", "{}").statusCode()).isEqualTo(405);
            assertThat(get(h, "/nothing").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void theApiAnswersWhileTheIssuerIsDownAndShowsWhatIsWaiting() throws Exception {
        try (NetHarness h = new NetHarness(); NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
            post(h, "/chaos", "{\"issuerDown\":true}");
            h.waitForLinkDown();
            acquirer.exchange(h.purchase(PAN, "5.00"));

            Await.until("the status shows the waiting advice", () -> {
                try {
                    return get(h, "/status").body().contains("\"advicesWaiting\":1");
                } catch (Exception e) {
                    return false;
                }
            });
            assertThat(get(h, "/status").body()).contains("\"standInApprovals\":1");
        }
    }
}
