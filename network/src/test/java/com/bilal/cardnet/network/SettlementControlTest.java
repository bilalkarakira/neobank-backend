package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.shared.ClearingDirs;
import com.bilal.cardnet.shared.testing.FakeIssuerClearing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static com.bilal.cardnet.network.ClearingProcessorTest.PAN;
import static com.bilal.cardnet.network.ClearingProcessorTest.de4;
import static com.bilal.cardnet.network.ClearingProcessorTest.purchase;
import static org.assertj.core.api.Assertions.assertThat;

/** The settlement endpoints of the network's control API. */
class SettlementControlTest {

    @TempDir
    Path root;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> call(NetHarness h, String method, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h.network.controlPort() + path));
        return http.send(method.equals("GET") ? request.GET().build() : request.POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void clearOneDay(NetHarness h) {
        ClearingFileCodec codec = ClearingFileCodec.withSecret("control-secret");
        codec.write(new ClearingDirs(root).acquirerOut(), "B1.clr",
                new ClearingFile("B1", LocalDate.of(2026, 10, 8), "123456", "NET", "USD", List.of(purchase(PAN, 1, de4("50.00")))));
        FakeIssuerClearing issuer = new FakeIssuerClearing(root, "control-secret");
        for (int i = 0; i < 3; i++) {
            h.network.clearing().tick();
            issuer.tick();
        }
        h.network.clearing().tick();
    }

    @Test
    void aRunThroughTheApiSettlesAndListsIt() throws Exception {
        try (NetHarness h = new NetHarness(c -> {
            c.clearingDir = root;
            c.fileSecret = "control-secret";
            c.clearingPoll = java.time.Duration.ofHours(1);
        })) {
            HttpResponse<String> nothing = call(h, "POST", "/settlement/run");
            assertThat(nothing.statusCode()).isEqualTo(200);
            assertThat(nothing.body()).contains("\"settled\":false").contains("Nothing to settle.");

            clearOneDay(h);
            assertThat(call(h, "GET", "/status").body()).contains("\"unsettledItems\":1");

            HttpResponse<String> run = call(h, "POST", "/settlement/run?date=2026-10-08");
            assertThat(run.statusCode()).isEqualTo(200);
            assertThat(run.body()).contains("\"settled\":true").contains("\"runNumber\":\"S0000001\"").contains("\"valueDate\":\"2026-10-09\"")
                    .contains("Settled 1 item(s) of 2026-10-08 as run S0000001, value date 2026-10-09.")
                    .contains("\"participant\":\"999900\"").contains("\"role\":\"ISSUER\"").contains("\"net\":-49.43").contains("\"net\":49.37")
                    .contains("\"net\":0.06");

            HttpResponse<String> list = call(h, "GET", "/settlements");
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(list.body()).contains("\"runNumber\":\"S0000001\"").contains("\"unsettledItems\":0");
            assertThat(call(h, "GET", "/status").body()).contains("\"unsettledItems\":0");
            assertThat(call(h, "POST", "/settlement/run?date=2026-10-08").body()).contains("\"settled\":false");
        }
    }

    @Test
    void badRequestsAreAnsweredPlainly() throws Exception {
        try (NetHarness h = new NetHarness(c -> {
            c.clearingDir = root;
            c.fileSecret = "control-secret";
            c.clearingPoll = java.time.Duration.ofHours(1);
        })) {
            HttpResponse<String> badDate = call(h, "POST", "/settlement/run?date=yesterday");
            assertThat(badDate.statusCode()).isEqualTo(400);
            assertThat(badDate.body()).contains("2026-10-06");
            assertThat(call(h, "GET", "/settlement/run").statusCode()).isEqualTo(405);
            assertThat(call(h, "POST", "/settlements").statusCode()).isEqualTo(405);
        }
    }

    @Test
    void withoutAClearingFolderSettlementIsOff() throws Exception {
        try (NetHarness h = new NetHarness()) {
            HttpResponse<String> run = call(h, "POST", "/settlement/run");
            assertThat(run.statusCode()).isEqualTo(409);
            assertThat(run.body()).contains("switched off");
            assertThat(call(h, "GET", "/settlements").body()).isEqualTo("{\"runs\":[]}");
            assertThat(h.network.settlement()).isNull();
        }
    }
}
