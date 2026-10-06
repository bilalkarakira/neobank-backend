package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.shared.ClearingDirs;
import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.testing.FakeIssuerClearing;
import com.bilal.cardnet.shared.testing.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

/** The network's clearing: file checks, item checks, fees, the split per issuer, acknowledgements, crash repair. */
class ClearingProcessorTest {

    static final String SECRET = "network-test-secret";
    static final String PAN = "9999000000000012";
    static final String OTHER_ISSUER_PAN = "8888000000000013";
    static final String NO_ROUTE_PAN = "7777000000000014";

    @TempDir
    Path root;

    final DataSource ds = TestDatabase.fresh();
    final PanVault vault = new PanVault("t".repeat(40));
    final NetworkConfig config = new NetworkConfig();
    ClearingDirs dirs;
    ClearingFileCodec codec;
    FakeIssuerClearing issuer;
    ClearingProcessor processor;

    @BeforeEach
    void setUp() {
        config.clearingDir = root;
        config.fileSecret = SECRET;
        config.routes = List.of(new NetworkConfig.Route("999900", "127.0.0.1", 1), new NetworkConfig.Route("888800", "127.0.0.1", 2));
        dirs = new ClearingDirs(root);
        codec = ClearingFileCodec.withSecret(SECRET);
        issuer = new FakeIssuerClearing(root, SECRET);
        processor = new ClearingProcessor(config, ds, vault, Clock.systemUTC());
    }

    // ---- building files ----

    static Message purchase(String pan, int n, String amount) {
        return Message.of("1240").set(2, pan).set(3, "000000").set(4, amount).set(7, "0101120000").set(11, "%06d".formatted(n))
                .set(12, "120000").set(13, "0101").set(14, "2912").set(18, "5411").set(32, "123456").set(37, "%012d".formatted(n))
                .set(38, "A1B2C3").set(41, "TERM0001").set(42, "MERCHANT000001 ").set(43, "%-40s".formatted("Corner Cafe")).set(49, "840");
    }

    static Message refund(String pan, int n, String amount) {
        return purchase(pan, n, amount).set(3, "200000").remove(38);
    }

    static String de4(String amount) {
        return "%012d".formatted(new BigDecimal(amount).movePointRight(2).longValueExact());
    }

    ClearingFile file(String id, Message... items) {
        return new ClearingFile(id, LocalDate.of(2026, 1, 1), "123456", "NET", "USD", List.of(items));
    }

    void put(String id, Message... items) {
        codec.write(dirs.acquirerOut(), id + ".clr", file(id, items));
    }

    ClearingAck ackOf(String id) {
        return codec.readAck(dirs.acquirerIn().resolve(id + ".ack"));
    }

    boolean ackExists(String id) {
        return Files.exists(dirs.acquirerIn().resolve(id + ".ack"));
    }

    /** Runs the network and the fake issuer until nothing moves any more. */
    void settle() {
        for (int i = 0; i < 6; i++) {
            processor.tick();
            issuer.tick();
        }
        processor.tick();
    }

    // ---- the happy path ----

    @Test
    void aFileIsForwardedToItsIssuerAndAcknowledgedAfterTheIssuerAnswers() throws Exception {
        put("B1", purchase(PAN, 1, de4("50.00")), purchase(PAN, 2, de4("12.50")), refund(PAN, 3, de4("5.00")));

        processor.tick();

        List<Path> issuerFiles = list(dirs.issuerIn());
        assertThat(issuerFiles).hasSize(1);
        ClearingFile forwarded = codec.read(issuerFiles.get(0));
        assertThat(forwarded.sender()).isEqualTo("NET");
        assertThat(forwarded.receiver()).isEqualTo("999900");
        assertThat(forwarded.currency()).isEqualTo("USD");
        assertThat(forwarded.businessDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(forwarded.presentments()).containsExactly(purchase(PAN, 1, de4("50.00")), purchase(PAN, 2, de4("12.50")), refund(PAN, 3, de4("5.00")));
        assertThat(forwarded.fileId()).startsWith("N");
        assertThat(ackExists("B1")).as("the acquirer waits for the issuer").isFalse();

        issuer.tick();
        processor.tick();

        ClearingAck ack = ackOf("B1");
        assertThat(ack.status()).isEqualTo(ClearingAck.Status.ACCEPTED);
        assertThat(ack.fileId()).isEqualTo("B1");
        assertThat(ack.items()).extracting(ClearingAck.Item::record).containsExactly(1, 2, 3);
        assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("POSTED", "POSTED", "REFUND_POSTED");
        assertThat(sql("select count(*) from net_clearing_files where status = 'ACCEPTED' and ack_written")).isEqualTo("1");
        assertThat(sql("select item_count || '/' || control_total from net_clearing_outfiles")).isEqualTo("3/6750");
        assertThat(processor.waiting()).isZero();
    }

    @Test
    void theIssuersVerdictsAreRelayedAsTheyAre() {
        put("B1", purchase(PAN, 1, de4("10.00")), purchase(PAN, 2, de4("10.00")), purchase(PAN, 3, de4("10.00")), purchase(PAN, 4, de4("10.00")));
        issuer.decision = m -> switch (m.get(11)) {
            case "000001" -> new FakeIssuerClearing.Verdict("POSTED", "");
            case "000002" -> new FakeIssuerClearing.Verdict("FORCE_POSTED", "unknown authorization");
            case "000003" -> new FakeIssuerClearing.Verdict("DUPLICATE", "already captured");
            default -> new FakeIssuerClearing.Verdict("REJECTED", "the authorization was reversed");
        };

        settle();

        ClearingAck ack = ackOf("B1");
        assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("POSTED", "FORCE_POSTED", "DUPLICATE", "REJECTED");
        assertThat(ack.items().get(3).reason()).isEqualTo("the authorization was reversed");
        assertThat(ack.items()).extracting(ClearingAck.Item::cleared).containsExactly(true, true, true, false);
    }

    // ---- fees ----

    @Test
    void feesAreWorkedOutPerItemRoundedHalfUpAndNegativeForRefunds() {
        put("B1", purchase(PAN, 1, de4("50.00")), refund(PAN, 2, de4("57.50")), purchase(PAN, 3, de4("0.10")), purchase(PAN, 4, de4("10.00")));

        processor.tick();

        assertThat(fees(1)).containsExactly("1.00", "0.60", "0.03", "0.03");
        assertThat(fees(2)).containsExactly("-1.15", "-0.69", "-0.03", "-0.03");
        assertThat(fees(3)).as("0.10: 0.002, 0.0012, 0.00005 all round to zero").containsExactly("0.00", "0.00", "0.00", "0.00");
        assertThat(fees(4)).as("10.00: the scheme fee 0.005 rounds UP to 0.01").containsExactly("0.20", "0.12", "0.01", "0.01");
    }

    @Test
    void feeRoundingBoundaries() {
        assertThat(Fees.of(new BigDecimal("0.25"), false, config).merchantDiscount()).as("0.0050").isEqualByComparingTo("0.01");
        assertThat(Fees.of(new BigDecimal("0.24"), false, config).merchantDiscount()).as("0.0048").isEqualByComparingTo("0.00");
        assertThat(Fees.of(new BigDecimal("1.00"), false, config).schemeFeeIssuer()).as("0.0005").isEqualByComparingTo("0.00");
        assertThat(Fees.of(new BigDecimal("10.00"), true, config).schemeFeeAcquirer()).isEqualByComparingTo("-0.01");
        assertThat(Fees.of(new BigDecimal("100.00"), false, config).interchange()).isEqualByComparingTo("1.20");
        assertThat(Fees.of(new BigDecimal("0.01"), true, config).merchantDiscount().signum()).as("no negative zero").isZero();
    }

    // ---- the split ----

    @Test
    void itemsAreSplitPerIssuerAndTheAcknowledgementKeepsTheOriginalOrder() {
        put("B1", purchase(PAN, 1, de4("10.00")), purchase(OTHER_ISSUER_PAN, 2, de4("20.00")), purchase(PAN, 3, de4("30.00")),
                purchase(OTHER_ISSUER_PAN, 4, de4("40.00")));
        issuer.decision = m -> new FakeIssuerClearing.Verdict(m.get(2).startsWith("8888") ? "REJECTED" : "POSTED", m.get(4));

        settle();

        assertThat(issuer.received).hasSize(2);
        ClearingFile first = issuer.received.stream().filter(f -> f.receiver().equals("999900")).findFirst().orElseThrow();
        ClearingFile second = issuer.received.stream().filter(f -> f.receiver().equals("888800")).findFirst().orElseThrow();
        assertThat(first.presentments()).extracting(m -> m.get(11)).containsExactly("000001", "000003");
        assertThat(second.presentments()).extracting(m -> m.get(11)).containsExactly("000002", "000004");
        assertThat(first.fileId()).isNotEqualTo(second.fileId());
        ClearingAck ack = ackOf("B1");
        assertThat(ack.items()).extracting(ClearingAck.Item::record).containsExactly(1, 2, 3, 4);
        assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("POSTED", "REJECTED", "POSTED", "REJECTED");
        assertThat(ack.items()).extracting(ClearingAck.Item::reason).containsExactly("000000001000", "000000002000", "000000003000", "000000004000");
    }

    // ---- item refusals ----

    @Test
    void anItemThatCannotBeRoutedOrIsWrongIsRefusedWhileTheRestProceed() {
        Message badCurrency = purchase(PAN, 2, de4("10.00")).set(49, "978");
        Message wrongAcquirer = purchase(PAN, 3, de4("10.00")).set(32, "999999");
        put("B1", purchase(PAN, 1, de4("10.00")), badCurrency, wrongAcquirer, purchase(NO_ROUTE_PAN, 4, de4("10.00")), purchase(PAN, 5, de4("10.00")));

        settle();

        ClearingFile forwarded = issuer.received.get(0);
        assertThat(forwarded.presentments()).extracting(m -> m.get(11)).containsExactly("000001", "000005");
        ClearingAck ack = ackOf("B1");
        assertThat(ack.status()).isEqualTo(ClearingAck.Status.ACCEPTED);
        assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("POSTED", "REFUSED", "REFUSED", "REFUSED", "POSTED");
        assertThat(ack.items().get(1).reason()).startsWith("CURRENCY");
        assertThat(ack.items().get(2).reason()).startsWith("ACQUIRER_MISMATCH");
        assertThat(ack.items().get(3).reason()).startsWith("NO_ROUTE");
        assertThat(sql("select count(*) from net_clearing_items where result = 'REFUSED' and presentment_ciphertext is null")).isEqualTo("3");
        assertThat(sql("select count(*) from net_clearing_items where result = 'REFUSED' and merchant_discount <> 0")).isEqualTo("0");
    }

    @Test
    void aFileWhoseItemsAreAllRefusedIsAcknowledgedAtOnceWithoutAnIssuerFile() {
        put("B1", purchase(NO_ROUTE_PAN, 1, de4("10.00")));

        processor.tick();

        assertThat(list(dirs.issuerIn())).isEmpty();
        assertThat(ackOf("B1").items()).extracting(ClearingAck.Item::result).containsExactly("REFUSED");
    }

    @Test
    void anEmptyFileIsAcceptedWithAnEmptyAcknowledgement() {
        put("B1");

        processor.tick();

        assertThat(ackOf("B1").status()).isEqualTo(ClearingAck.Status.ACCEPTED);
        assertThat(ackOf("B1").items()).isEmpty();
        assertThat(list(dirs.issuerIn())).isEmpty();
    }

    // ---- files refused whole ----

    private String refusal(String id) {
        ClearingAck ack = ackOf(id);
        assertThat(ack.status()).isEqualTo(ClearingAck.Status.REJECTED);
        assertThat(list(dirs.issuerIn())).as("a refused file reaches no issuer").isEmpty();
        assertThat(sql("select count(*) from net_clearing_items")).as("a refused file processes nothing").isEqualTo("0");
        return ack.reason();
    }

    @Test
    void aFileThatCannotBeDecryptedIsRefused() throws Exception {
        ClearingFileCodec other = ClearingFileCodec.withSecret("somebody else");
        other.write(dirs.acquirerOut(), "B1.clr", file("B1", purchase(PAN, 1, de4("10.00"))));
        processor.tick();
        assertThat(refusal("B1")).startsWith("DECRYPT_FAILED");
    }

    @Test
    void aTamperedFileIsRefused() throws Exception {
        put("B1", purchase(PAN, 1, de4("10.00")));
        Path path = dirs.acquirerOut().resolve("B1.clr");
        byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length / 2] ^= 1;
        Files.write(path, bytes);
        processor.tick();
        assertThat(refusal("B1")).startsWith("DECRYPT_FAILED");
    }

    @Test
    void aFileThatIsNotAClearingFileIsRefused() throws Exception {
        Files.writeString(dirs.acquirerOut().resolve("B1.clr"), "hello", StandardCharsets.US_ASCII);
        processor.tick();
        assertThat(refusal("B1")).startsWith("NOT_A_CLEARING_FILE");
    }

    @Test
    void aRenamedFileIsRefusedBecauseTheNameIsPartOfTheCheck() throws Exception {
        put("B1", purchase(PAN, 1, de4("10.00")));
        Files.move(dirs.acquirerOut().resolve("B1.clr"), dirs.acquirerOut().resolve("B2.clr"));
        processor.tick();
        assertThat(refusal("B2")).startsWith("DECRYPT_FAILED");
    }

    @Test
    void aFileIdThatDoesNotMatchTheFileNameIsRefused() {
        codec.write(dirs.acquirerOut(), "B9.clr", file("B1", purchase(PAN, 1, de4("10.00"))));
        processor.tick();
        assertThat(refusal("B9")).startsWith("FILE_NAME_MISMATCH");
    }

    @Test
    void anUnknownSenderIsRefused() {
        codec.write(dirs.acquirerOut(), "B1.clr", new ClearingFile("B1", LocalDate.of(2026, 1, 1), "654321", "NET", "USD", List.of(purchase(PAN, 1, de4("10.00")))));
        processor.tick();
        assertThat(refusal("B1")).startsWith("UNKNOWN_SENDER");
    }

    @Test
    void aFileAddressedToSomeoneElseIsRefused() {
        codec.write(dirs.acquirerOut(), "B1.clr", new ClearingFile("B1", LocalDate.of(2026, 1, 1), "123456", "OTHERNET", "USD", List.of(purchase(PAN, 1, de4("10.00")))));
        processor.tick();
        assertThat(refusal("B1")).startsWith("WRONG_RECEIVER");
    }

    @Test
    void aFileWithTheRightMagicButNothingInsideIsRefusedAndDoesNotDisturbTheNextFile() throws Exception {
        put("B1", purchase(PAN, 1, de4("10.00")));
        put("B2", purchase(PAN, 2, de4("10.00")));
        Files.write(dirs.acquirerOut().resolve("B1.clr"), new byte[] {'N', 'C', 'L', 'R', '1', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        processor.tick();
        assertThat(ackOf("B1").status()).isEqualTo(ClearingAck.Status.REJECTED);
        assertThat(ackOf("B1").reason()).startsWith("DECRYPT_FAILED");
        issuer.tick();
        processor.tick();
        assertThat(ackOf("B2").status()).isEqualTo(ClearingAck.Status.ACCEPTED);
    }

    @Test
    void aFileSeenTwiceIsProcessedOnceAndItsAcknowledgementIsNotRewritten() throws Exception {
        put("B1", purchase(PAN, 1, de4("10.00")));
        settle();
        Path ackPath = dirs.acquirerIn().resolve("B1.ack");
        byte[] before = Files.readAllBytes(ackPath);
        int issuerFiles = list(dirs.issuerIn()).size();

        // the acquirer leaves the very same file again (it crashed after writing it, say)
        put("B1", purchase(PAN, 1, de4("10.00")));
        settle();

        assertThat(Files.readAllBytes(ackPath)).isEqualTo(before);
        assertThat(list(dirs.issuerIn())).hasSize(issuerFiles);
        assertThat(sql("select count(*) from net_clearing_items")).isEqualTo("1");
        assertThat(sql("select count(*) from net_clearing_files")).isEqualTo("1");
    }

    @Test
    void aRefusedFileIsNotReprocessedOnTheNextBeat() throws Exception {
        Files.writeString(dirs.acquirerOut().resolve("B1.clr"), "hello", StandardCharsets.US_ASCII);
        processor.tick();
        byte[] first = Files.readAllBytes(dirs.acquirerIn().resolve("B1.ack"));

        assertThat(processor.tick()).isZero();
        assertThat(Files.readAllBytes(dirs.acquirerIn().resolve("B1.ack"))).isEqualTo(first);
        assertThat(sql("select count(*) from net_clearing_files")).isEqualTo("1");
    }

    @Test
    void filesStillBeingWrittenAndOtherFilesAreIgnored() throws Exception {
        Files.writeString(dirs.acquirerOut().resolve("B1.clr.tmp"), "half", StandardCharsets.US_ASCII);
        Files.writeString(dirs.acquirerOut().resolve("notes.txt"), "x", StandardCharsets.US_ASCII);

        assertThat(processor.tick()).isZero();
        assertThat(sql("select count(*) from net_clearing_files")).isEqualTo("0");
    }

    // ---- the issuer's side going wrong ----

    @Test
    void theAcquirerWaitsWhileTheIssuerIsSilentAndGetsTheAnswerWhenItComes() {
        put("B1", purchase(PAN, 1, de4("10.00")));
        issuer.silent = true;

        for (int i = 0; i < 5; i++) {
            processor.tick();
        }
        assertThat(ackExists("B1")).isFalse();
        assertThat(processor.waiting()).isEqualTo(1);

        issuer.silent = false;
        settle();
        assertThat(ackOf("B1").items()).extracting(ClearingAck.Item::result).containsExactly("POSTED");
    }

    @Test
    void anIssuerThatRefusesTheFileWholeMakesEveryItemRefused() {
        put("B1", purchase(PAN, 1, de4("10.00")), purchase(PAN, 2, de4("10.00")));
        issuer.rejectFiles = "HASH_MISMATCH: broken";

        settle();

        ClearingAck ack = ackOf("B1");
        assertThat(ack.status()).isEqualTo(ClearingAck.Status.ACCEPTED);
        assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("REFUSED", "REFUSED");
        assertThat(ack.items().get(0).reason()).contains("issuer rejected the file").contains("HASH_MISMATCH");
    }

    @Test
    void anAnswerForTheWrongFileOrAnUnreadableAnswerIsNotTrusted() throws Exception {
        put("B1", purchase(PAN, 1, de4("10.00")));
        issuer.answerWithWrongFileId = true;
        settle();
        assertThat(ackOf("B1").items()).extracting(ClearingAck.Item::result).containsExactly("REFUSED");
        assertThat(ackOf("B1").items().get(0).reason()).contains("unusable");

        put("B2", purchase(PAN, 2, de4("10.00")));
        processor.tick();
        String out = sql("select o.file_name from net_clearing_outfiles o join net_clearing_files f on f.id = o.file_row where f.file_id = 'B2'");
        Files.writeString(dirs.issuerOut().resolve(ClearingDirs.ackName(out)), "garbage", StandardCharsets.US_ASCII);
        processor.tick();
        processor.tick();
        assertThat(ackOf("B2").items()).extracting(ClearingAck.Item::result).containsExactly("REFUSED");
        assertThat(sql("select ack_status from net_clearing_outfiles where file_name = '" + out + "'")).isEqualTo("INVALID");
    }

    @Test
    void anAnswerThatLeavesAnItemOutRefusesThatItem() throws Exception {
        put("B1", purchase(PAN, 1, de4("10.00")), purchase(PAN, 2, de4("10.00")));
        processor.tick();
        String name = sql("select file_name from net_clearing_outfiles");
        String fileId = sql("select file_id from net_clearing_outfiles");
        codec.writeAck(dirs.issuerOut(), ClearingDirs.ackName(name), ClearingAck.accepted(fileId, List.of(new ClearingAck.Item(1, "POSTED", ""))));

        processor.tick();
        processor.tick();

        assertThat(ackOf("B1").items()).extracting(ClearingAck.Item::result).containsExactly("POSTED", "REFUSED");
        assertThat(ackOf("B1").items().get(1).reason()).contains("does not mention");
    }

    // ---- crash repair ----

    @Test
    void aCrashBeforeTheIssuerFileWasWrittenIsRepairedWithTheSameFile() throws Exception {
        put("B1", purchase(PAN, 1, de4("10.00")));
        processor.tick();
        Path issuerFile = list(dirs.issuerIn()).get(0);
        ClearingFile original = codec.read(issuerFile);
        Files.delete(issuerFile);
        update("update net_clearing_outfiles set written = false");

        processor.tick();

        assertThat(list(dirs.issuerIn())).hasSize(1);
        ClearingFile repaired = codec.read(list(dirs.issuerIn()).get(0));
        assertThat(repaired).isEqualTo(original);
        assertThat(repaired.fileId()).isEqualTo(original.fileId());
    }

    @Test
    void aCrashBeforeTheAcknowledgementWasWrittenIsRepaired() throws Exception {
        put("B1", purchase(PAN, 1, de4("10.00")));
        settle();
        Files.delete(dirs.acquirerIn().resolve("B1.ack"));
        update("update net_clearing_files set ack_written = false");

        processor.tick();

        assertThat(ackOf("B1").items()).extracting(ClearingAck.Item::result).containsExactly("POSTED");
    }

    @Test
    void aRestartedProcessorContinuesWhereTheOldOneStopped() {
        put("B1", purchase(PAN, 1, de4("10.00")));
        processor.tick();
        processor = new ClearingProcessor(config, ds, vault, Clock.systemUTC());

        issuer.tick();
        processor.tick();

        assertThat(ackOf("B1").items()).extracting(ClearingAck.Item::result).containsExactly("POSTED");
        assertThat(list(dirs.issuerIn())).hasSize(1);
    }

    @Test
    void twoProcessorsLookingAtTheSameFileProcessItOnce() throws Exception {
        put("B1", purchase(PAN, 1, de4("10.00")), purchase(PAN, 2, de4("20.00")));
        ClearingProcessor second = new ClearingProcessor(config, ds, vault, Clock.systemUTC());
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Throwable> failures = new ArrayList<>();
        Thread a = new Thread(() -> run(barrier, processor, failures));
        Thread b = new Thread(() -> run(barrier, second, failures));
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(failures).isEmpty();
        assertThat(sql("select count(*) from net_clearing_files where file_id = 'B1' and status = 'ACCEPTED'")).isEqualTo("1");
        assertThat(sql("select count(*) from net_clearing_items")).isEqualTo("2");
        assertThat(sql("select count(*) from net_clearing_outfiles")).isEqualTo("1");
        settle();
        assertThat(ackOf("B1").status()).isEqualTo(ClearingAck.Status.ACCEPTED);
    }

    private static void run(CyclicBarrier barrier, ClearingProcessor p, List<Throwable> failures) {
        try {
            barrier.await();
            p.tick();
        } catch (Throwable t) {
            synchronized (failures) {
                failures.add(t);
            }
        }
    }

    // ---- secrets ----

    @Test
    void noCardNumberIsKeptInTheDatabaseOrTheFolders() throws Exception {
        put("B1", purchase(PAN, 1, de4("10.00")), purchase(OTHER_ISSUER_PAN, 2, de4("10.00")), purchase(NO_ROUTE_PAN, 3, de4("10.00")));
        settle();

        for (String table : List.of("net_clearing_files", "net_clearing_outfiles", "net_clearing_items")) {
            for (String text : textOf(table)) {
                for (String pan : List.of(PAN, OTHER_ISSUER_PAN, NO_ROUTE_PAN)) {
                    assertThat(text).as(table).doesNotContain(pan);
                }
            }
        }
        assertThat(sql("select masked_pan from net_clearing_items where record_no = 1")).isEqualTo("999900******0012");
        try (var all = Files.walk(root)) {
            for (Path p : all.filter(Files::isRegularFile).toList()) {
                String content = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                assertThat(content).as(p.toString()).doesNotContain(PAN).doesNotContain("FILEHDR");
            }
        }
    }

    // ---- helpers ----

    private List<String> fees(int record) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select merchant_discount, interchange, scheme_fee_acquirer, scheme_fee_issuer from net_clearing_items where record_no = ?")) {
            ps.setInt(1, record);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return List.of(rs.getBigDecimal(1).toPlainString(), rs.getBigDecimal(2).toPlainString(), rs.getBigDecimal(3).toPlainString(),
                        rs.getBigDecimal(4).toPlainString());
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> list(Path dir) {
        try (var s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String sql(String sql) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void update(String sql) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every non-binary column of every row, as text. */
    private List<String> textOf(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select * from " + table); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    if (meta.getColumnTypeName(i).equals("bytea")) {
                        continue;
                    }
                    row.append(rs.getString(i)).append('|');
                }
                out.add(row.toString());
            }
        }
        return out;
    }
}
