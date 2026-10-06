package com.bilal.cardnet.acquirer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;

import static com.bilal.cardnet.acquirer.AcqHarness.PAN;
import static org.assertj.core.api.Assertions.assertThat;

/** The console's end-of-day surface: the page, the endpoints, their errors. */
class ClearingConsoleTest {

    @TempDir
    Path root;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(AcqHarness h, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h.acquirer.consolePort() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(AcqHarness h, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h.acquirer.consolePort() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String idBody(UUID id, String amount) {
        return "{\"id\":\"" + id + "\",\"amount\":\"" + amount + "\"}";
    }

    @Test
    void thePageHasTheEndOfDaySectionAndTheConfigSaysClearingIsOn() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            String page = get(h, "/").body();
            assertThat(page).contains("End of day: capture and clearing").contains("Close the day").contains("Set amount").contains("Refund");
            assertThat(page).as("no card number of its own").doesNotContain(PAN);
            String config = get(h, "/api/config").body();
            assertThat(config).contains("\"clearing\":true").contains("\"tipLimitPercent\":\"20\"");
        }
    }

    @Test
    void aWholeDayThroughTheApi() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            UUID id = h.payApproved("50.00");

            HttpResponse<String> tip = post(h, "/api/adjust", idBody(id, "57.50"));
            assertThat(tip.statusCode()).isEqualTo(200);
            assertThat(tip.body()).contains("\"finalAmount\":\"57.50\"").contains("\"amount\":\"50.00\"").contains("\"kind\":\"PURCHASE\"");

            HttpResponse<String> close = post(h, "/api/close-day", "");
            assertThat(close.statusCode()).isEqualTo(200);
            assertThat(close.body()).contains("\"count\":1").contains("\"controlTotal\":5750").contains("ACQ").contains("Sent 1 item(s)");
            assertThat(get(h, "/api/transactions").body()).contains("\"status\":\"BATCHED\"");
            assertThat(get(h, "/api/batches").body()).contains("\"status\":\"SUBMITTED\"").contains("\"controlTotal\":\"57.50\"").contains("\"count\":1");

            h.pumpUntil("cleared", () -> h.status(id).equals("CLEARED"));
            assertThat(get(h, "/api/transactions").body()).contains("\"status\":\"CLEARED\"");
            assertThat(get(h, "/api/batches").body()).contains("\"status\":\"ACCEPTED\"");

            HttpResponse<String> refund = post(h, "/api/refund", idBody(id, "7.50"));
            assertThat(refund.statusCode()).isEqualTo(200);
            assertThat(refund.body()).contains("\"kind\":\"REFUND\"").contains("\"status\":\"REFUND_DUE\"").contains("\"refundOf\":\"" + id + "\"");
            assertThat(post(h, "/api/close-day", "").body()).contains("\"count\":1").contains("\"controlTotal\":750");
        }
    }

    @Test
    void nothingToSendIsSaidPlainly() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            HttpResponse<String> close = post(h, "/api/close-day", "");
            assertThat(close.statusCode()).isEqualTo(200);
            assertThat(close.body()).contains("\"batch\":null").contains("Nothing to send");
            assertThat(get(h, "/api/batches").body()).contains("\"batches\":[]");
        }
    }

    @Test
    void errorsHaveTheRightStatusAndAReadableMessage() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            UUID id = h.payApproved("50.00");

            assertThat(post(h, "/api/adjust", idBody(id, "60.01")).statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/adjust", idBody(id, "60.01")).body()).contains("at most 60.00");
            assertThat(post(h, "/api/adjust", idBody(id, "abc")).statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/adjust", "{\"id\":\"nope\",\"amount\":\"5\"}").statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/adjust", "{\"amount\":\"5\"}").statusCode()).isEqualTo(400);
            assertThat(post(h, "/api/adjust", idBody(UUID.randomUUID(), "5.00")).statusCode()).isEqualTo(404);
            assertThat(post(h, "/api/refund", idBody(id, "5.00")).statusCode()).as("not cleared yet").isEqualTo(409);
            assertThat(post(h, "/api/refund", idBody(UUID.randomUUID(), "5.00")).statusCode()).isEqualTo(404);

            post(h, "/api/close-day", "");
            assertThat(post(h, "/api/adjust", idBody(id, "55.00")).statusCode()).as("already in a batch").isEqualTo(409);

            assertThat(get(h, "/api/adjust").statusCode()).isEqualTo(405);
            assertThat(get(h, "/api/refund").statusCode()).isEqualTo(405);
            assertThat(get(h, "/api/close-day").statusCode()).isEqualTo(405);
            assertThat(post(h, "/api/batches", "").statusCode()).isEqualTo(405);
        }
    }

    @Test
    void closingTheDayWithoutAFolderIsAConflict() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            HttpResponse<String> close = post(h, "/api/close-day", "");
            assertThat(close.statusCode()).isEqualTo(409);
            assertThat(close.body()).contains("switched off");
            assertThat(get(h, "/api/config").body()).contains("\"clearing\":false");
        }
    }
}
