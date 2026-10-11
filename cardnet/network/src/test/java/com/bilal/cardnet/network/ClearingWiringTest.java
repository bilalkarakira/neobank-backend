package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.shared.ClearingDirs;
import com.bilal.cardnet.shared.testing.Await;
import com.bilal.cardnet.shared.testing.FakeIssuerClearing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static com.bilal.cardnet.network.ClearingProcessorTest.PAN;
import static com.bilal.cardnet.network.ClearingProcessorTest.de4;
import static com.bilal.cardnet.network.ClearingProcessorTest.purchase;
import static org.assertj.core.api.Assertions.assertThat;

/** The running network picks clearing files up by itself, and stays quiet when no folder is configured. */
class ClearingWiringTest {

    @TempDir
    Path root;

    @Test
    void theBackgroundBeatProcessesFilesWithoutHelp() throws Exception {
        try (NetHarness h = new NetHarness(c -> {
            c.clearingDir = root;
            c.fileSecret = "wiring-secret";
            c.clearingPoll = Duration.ofMillis(50);
        })) {
            ClearingDirs dirs = new ClearingDirs(root);
            ClearingFileCodec codec = ClearingFileCodec.withSecret("wiring-secret");
            FakeIssuerClearing issuer = new FakeIssuerClearing(root, "wiring-secret");
            codec.write(dirs.acquirerOut(), "B1.clr", new ClearingFile("B1", LocalDate.of(2026, 1, 1), "123456", "NET", "USD",
                    List.of(purchase(PAN, 1, de4("10.00")))));

            Await.until("the network forwarded the file", () -> {
                issuer.tick();
                return Files.exists(dirs.issuerOut().resolve(ClearingDirs.ackName(issuerFileName(dirs))));
            });
            Await.until("the acquirer's acknowledgement exists", () -> Files.exists(dirs.acquirerIn().resolve("B1.ack")));

            assertThat(codec.readAck(dirs.acquirerIn().resolve("B1.ack")).items()).extracting(ClearingAck.Item::result).containsExactly("POSTED");
            assertThat(h.network.status()).containsEntry("clearingFilesWaiting", 0);
        }
    }

    private static String issuerFileName(ClearingDirs dirs) {
        try (var list = Files.list(dirs.issuerIn())) {
            return list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".clr")).findFirst().orElse("none.clr");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void withoutAFolderClearingIsOffAndNothingIsCreated() throws Exception {
        try (NetHarness h = new NetHarness()) {
            assertThat(h.network.clearing()).isNull();
            assertThat(h.network.status()).doesNotContainKey("clearingFilesWaiting");
        }
    }
}
