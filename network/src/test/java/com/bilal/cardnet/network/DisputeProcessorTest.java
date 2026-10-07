package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.shared.ClearingDirs;
import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.testing.FakeIssuerClearing;
import com.bilal.cardnet.shared.testing.MutableClock;
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
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;

import static com.bilal.cardnet.network.ClearingProcessorTest.OTHER_ISSUER_PAN;
import static com.bilal.cardnet.network.ClearingProcessorTest.PAN;
import static com.bilal.cardnet.network.ClearingProcessorTest.de4;
import static com.bilal.cardnet.network.ClearingProcessorTest.purchase;
import static com.bilal.cardnet.network.ClearingProcessorTest.refund;
import static org.assertj.core.api.Assertions.assertThat;

/** Chargebacks and representments at the network: the checks, the forwarding, the acknowledgements, the fees, the settlement. */
class DisputeProcessorTest {

    static final String SECRET = "dispute-test-secret";
    static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    @TempDir
    Path root;

    final DataSource ds = TestDatabase.fresh();
    final PanVault vault = new PanVault("t".repeat(40));
    final NetworkConfig config = new NetworkConfig();
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T12:00:00Z"));
    ClearingDirs dirs;
    ClearingFileCodec codec;
    FakeIssuerClearing issuer;
    ClearingProcessor clearing;
    DisputeProcessor disputes;
    SettlementService settlement;

    @BeforeEach
    void setUp() {
        config.clearingDir = root;
        config.fileSecret = SECRET;
        config.routes = List.of(new NetworkConfig.Route("999900", "127.0.0.1", 1), new NetworkConfig.Route("888800", "127.0.0.1", 2));
        config.knownAcquirers = Set.of("123456", "654321");
        dirs = new ClearingDirs(root);
        codec = ClearingFileCodec.withSecret(SECRET);
        issuer = new FakeIssuerClearing(root, SECRET);
        clearing = new ClearingProcessor(config, ds, vault, clock);
        disputes = new DisputeProcessor(config, ds, vault, clock);
        settlement = new SettlementService(config, ds, clock);
    }

    // ---- building the world ----

    /** Clears purchases through the whole clearing pipeline, so the network really has the items. */
    private void clear(String id, LocalDate date, String acquirer, Message... items) {
        codec.write(dirs.acquirerOut(), id + ".clr", new ClearingFile(id, date, acquirer, "NET", "USD", List.of(items)));
        for (int i = 0; i < 4; i++) {
            clearing.tick();
            issuer.tick();
        }
        clearing.tick();
    }

    static Message chargeback(String pan, int n, String amount, String reason) {
        return Message.of("1442").set(2, pan).set(3, "000000").set(4, amount).set(7, "1006120000").set(11, "%06d".formatted(n))
                .set(12, "120000").set(13, "0101").set(32, "123456").set(37, "%012d".formatted(n)).set(41, "TERM0001")
                .set(42, "MERCHANT000001 ").set(49, "840").set(48, "CB|" + reason + "|my note");
    }

    static Message representment(String pan, int n, String amount) {
        return Message.of("1240").set(2, pan).set(3, "000000").set(4, amount).set(7, "1006130000").set(11, "%06d".formatted(n))
                .set(12, "130000").set(13, "0101").set(32, "123456").set(37, "%012d".formatted(n)).set(41, "TERM0001")
                .set(42, "MERCHANT000001 ").set(49, "840").set(48, "REP|signed receipt");
    }

    private void chargebacks(String id, LocalDate date, String issuerPrefix, Message... items) {
        codec.write(dirs.issuerOut(), id + ".dsp", new ClearingFile(id, date, issuerPrefix, "NET", "USD", List.of(items)));
    }

    private void representments(String id, LocalDate date, String acquirer, Message... items) {
        codec.write(dirs.acquirerOut(), id + ".dsp", new ClearingFile(id, date, acquirer, "NET", "USD", List.of(items)));
    }

    private void beat() {
        for (int i = 0; i < 3; i++) {
            disputes.tick();
        }
    }

    private ClearingAck issuerAck(String id) {
        return codec.readAck(dirs.issuerIn().resolve(id + ".ack"));
    }

    private ClearingAck acquirerAck(String id) {
        return codec.readAck(dirs.acquirerIn().resolve(id + ".ack"));
    }

    private static String results(ClearingAck ack) {
        return ack.items().stream().map(ClearingAck.Item::result).reduce((a, b) -> a + "," + b).orElse("");
    }

    // ---- a chargeback ----

    @Test
    void aChargebackIsCheckedForwardedToTheAcquirerAcknowledgedAndBecomesARefundLikeItem() throws Exception {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("100.00")));
        Message cb = chargeback(PAN, 1, de4("40.00"), "UNAUTHORIZED");
        chargebacks("D1", DAY, "999900", cb);

        beat();

        ClearingAck ack = issuerAck("D1");
        assertThat(ack.status()).isEqualTo(ClearingAck.Status.ACCEPTED);
        assertThat(results(ack)).isEqualTo("CHARGEBACK_ACCEPTED");
        List<Path> forwarded = list(dirs.acquirerIn(), ".dsp");
        assertThat(forwarded).hasSize(1);
        ClearingFile file = codec.read(forwarded.get(0));
        assertThat(file.sender()).isEqualTo("NET");
        assertThat(file.receiver()).isEqualTo("123456");
        assertThat(file.fileId()).isEqualTo("X0000001");
        assertThat(file.presentments()).containsExactly(cb);
        assertThat(sql("select kind || '/' || amount || '/' || merchant_discount || '/' || interchange || '/' || scheme_fee_acquirer || '/' || scheme_fee_issuer "
                + "|| '/' || result || '/' || issuer_prefix || '/' || acquirer_id || '/' || business_date from net_clearing_items where kind = 'CHARGEBACK'"))
                .as("fees reversed like a refund").isEqualTo("CHARGEBACK/40.00/-0.80/-0.48/-0.02/-0.02/CHARGEBACK_ACCEPTED/999900/123456/2026-10-02");
        assertThat(sql("select count(*) from net_clearing_items c join net_clearing_items p on p.id = c.original_item where c.kind = 'CHARGEBACK' and p.kind = 'PURCHASE'")).isEqualTo("1");
        assertThat(sql("select ack_written || '/' || (select written from net_dispute_forwards)::text from net_dispute_files")).isIn("t/t", "true/true");
    }

    @Test
    void theAmountBoundaryIsExact() {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("100.00")), purchase(PAN, 2, de4("100.00")));
        chargebacks("D1", DAY, "999900", chargeback(PAN, 1, de4("100.00"), "OTHER"), chargeback(PAN, 2, de4("100.01"), "OTHER"));

        beat();

        ClearingAck ack = issuerAck("D1");
        assertThat(results(ack)).isEqualTo("CHARGEBACK_ACCEPTED,REFUSED");
        assertThat(ack.items().get(1).reason()).startsWith("AMOUNT_TOO_LARGE");
    }

    @Test
    void thePaymentMustBeACleardPurchaseOfThatIssuerForThatAcquirer() {
        issuer.decision = m -> new FakeIssuerClearing.Verdict(m.get(11).equals("000004") ? "REJECTED" : "POSTED", "");
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("10.00")), refund(PAN, 2, de4("10.00")), purchase(OTHER_ISSUER_PAN, 3, de4("10.00")),
                purchase(PAN, 4, de4("10.00")));
        chargebacks("D1", DAY, "999900",
                chargeback(PAN, 9, de4("5.00"), "OTHER"),                        // never cleared
                chargeback(PAN, 2, de4("5.00"), "OTHER"),                        // a refund is not a purchase
                chargeback(OTHER_ISSUER_PAN, 3, de4("5.00"), "OTHER"),           // another issuer's payment
                chargeback(PAN, 1, de4("5.00"), "OTHER").set(32, "654321"),     // another acquirer's id
                chargeback(PAN, 4, de4("5.00"), "OTHER"));                       // the issuer refused to post it: never really paid

        beat();

        ClearingAck ack = issuerAck("D1");
        assertThat(results(ack)).isEqualTo("REFUSED,REFUSED,REFUSED,REFUSED,REFUSED");
        assertThat(ack.items()).allSatisfy(i -> assertThat(i.reason()).startsWith("NO_SUCH_PAYMENT"));
        assertThat(list(dirs.acquirerIn(), ".dsp")).as("nothing accepted, nothing forwarded").isEmpty();
        assertThat(sql("select count(*) from net_clearing_items where kind = 'CHARGEBACK' and result <> 'REFUSED'")).isEqualTo("0");
    }

    @Test
    void aPaymentCanBeChargedBackOnceNotTwice() {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("50.00")));
        chargebacks("D1", DAY, "999900", chargeback(PAN, 1, de4("20.00"), "OTHER"));
        beat();
        chargebacks("D2", DAY, "999900", chargeback(PAN, 1, de4("10.00"), "DUPLICATE"));

        beat();

        assertThat(results(issuerAck("D1"))).isEqualTo("CHARGEBACK_ACCEPTED");
        ClearingAck second = issuerAck("D2");
        assertThat(results(second)).isEqualTo("REFUSED");
        assertThat(second.items().get(0).reason()).startsWith("ALREADY_CHARGED_BACK");
        assertThat(sql("select count(*) from net_clearing_items where kind = 'CHARGEBACK' and result = 'CHARGEBACK_ACCEPTED'")).isEqualTo("1");
    }

    @Test
    void theAgeLimitIsExactlyOneHundredAndTwentyDays() {
        // the clock stands at 2026-10-06: a payment of 2026-06-08 is 120 days old, one of 2026-06-07 is 121
        clear("B1", LocalDate.of(2026, 6, 8), "123456", purchase(PAN, 1, de4("10.00")));
        clear("B2", LocalDate.of(2026, 6, 7), "123456", purchase(PAN, 2, de4("10.00")));
        chargebacks("D1", DAY, "999900", chargeback(PAN, 1, de4("10.00"), "OTHER"), chargeback(PAN, 2, de4("10.00"), "OTHER"));

        beat();

        ClearingAck ack = issuerAck("D1");
        assertThat(results(ack)).isEqualTo("CHARGEBACK_ACCEPTED,REFUSED");
        assertThat(ack.items().get(1).reason()).startsWith("TOO_OLD");
        assertThat(DisputeProcessor.CHARGEBACK_DAYS).isEqualTo(120);
    }

    @Test
    void aMixedFileIsAnsweredPerItemAndOnlyAcceptedItemsAreForwardedPerAcquirer() throws Exception {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("10.00")), purchase(PAN, 2, de4("20.00")));
        clear("B2", DAY, "654321", purchase(PAN, 3, de4("30.00")).set(32, "654321"));
        chargebacks("D1", DAY, "999900", chargeback(PAN, 1, de4("10.00"), "OTHER"), chargeback(PAN, 9, de4("1.00"), "OTHER"),
                chargeback(PAN, 2, de4("5.00"), "NOT_RECEIVED"), chargeback(PAN, 3, de4("30.00"), "DUPLICATE").set(32, "654321"));

        beat();

        assertThat(results(issuerAck("D1"))).isEqualTo("CHARGEBACK_ACCEPTED,REFUSED,CHARGEBACK_ACCEPTED,CHARGEBACK_ACCEPTED");
        List<Path> files = list(dirs.acquirerIn(), ".dsp");
        assertThat(files).hasSize(2);
        ClearingFile toFirst = files.stream().map(codec::read).filter(f -> f.receiver().equals("123456")).findFirst().orElseThrow();
        ClearingFile toSecond = files.stream().map(codec::read).filter(f -> f.receiver().equals("654321")).findFirst().orElseThrow();
        assertThat(toFirst.presentments()).extracting(m -> m.get(11)).containsExactly("000001", "000002");
        assertThat(toSecond.presentments()).extracting(m -> m.get(11)).containsExactly("000003");
        assertThat(toFirst.controlTotal()).isEqualTo(1500);
    }

    // ---- a representment ----

    private void chargedBack(int n, String amount) {
        chargebacks("D" + n, DAY, "999900", chargeback(PAN, n, de4(amount), "UNAUTHORIZED"));
        beat();
    }

    @Test
    void aRepresentmentAnswersAChargebackIsForwardedToTheIssuerAndBecomesAPurchaseLikeItem() throws Exception {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("100.00")));
        chargedBack(1, "40.00");
        Message rep = representment(PAN, 1, de4("40.00"));
        representments("R1", DAY, "123456", rep);

        beat();

        ClearingAck ack = acquirerAck("R1");
        assertThat(results(ack)).isEqualTo("REPRESENTMENT_ACCEPTED");
        List<Path> forwarded = list(dirs.issuerIn(), ".dsp");
        assertThat(forwarded).hasSize(1);
        ClearingFile file = codec.read(forwarded.get(0));
        assertThat(file.receiver()).isEqualTo("999900");
        assertThat(file.sender()).isEqualTo("NET");
        assertThat(file.presentments()).containsExactly(rep);
        assertThat(sql("select kind || '/' || amount || '/' || merchant_discount || '/' || interchange || '/' || scheme_fee_acquirer || '/' || issuer_prefix || '/' || acquirer_id "
                + "from net_clearing_items where kind = 'REPRESENTMENT'")).as("fees like a purchase").isEqualTo("REPRESENTMENT/40.00/0.80/0.48/0.02/999900/123456");
        assertThat(sql("select count(*) from net_clearing_items r join net_clearing_items c on c.id = r.original_item where r.kind = 'REPRESENTMENT' and c.kind = 'CHARGEBACK'")).isEqualTo("1");
    }

    @Test
    void aRepresentmentMayBeForLessButNeverForMoreAndOnlyOnceAndOnlyForARealChargebackOfThatAcquirer() {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("100.00")), purchase(PAN, 2, de4("100.00")), purchase(PAN, 3, de4("100.00")));
        chargedBack(1, "40.00");
        chargedBack(2, "40.00");
        representments("R1", DAY, "123456", representment(PAN, 1, de4("40.01")), representment(PAN, 2, de4("15.00")), representment(PAN, 3, de4("10.00")),
                representment(PAN, 9, de4("10.00")));
        beat();
        representments("R2", DAY, "123456", representment(PAN, 2, de4("1.00")));
        representments("R3", DAY, "654321", representment(PAN, 1, de4("5.00")).set(32, "654321"));
        // a different acquirer sending the right identifiers (DE 32 still names the real acquirer) must not answer someone else's chargeback
        representments("R4", DAY, "654321", representment(PAN, 1, de4("5.00")));

        beat();

        ClearingAck first = acquirerAck("R1");
        assertThat(results(first)).isEqualTo("REFUSED,REPRESENTMENT_ACCEPTED,REFUSED,REFUSED");
        assertThat(first.items().get(0).reason()).startsWith("AMOUNT_TOO_LARGE");
        assertThat(first.items().get(2).reason()).startsWith("NO_SUCH_CHARGEBACK");
        assertThat(first.items().get(3).reason()).startsWith("NO_SUCH_CHARGEBACK");
        assertThat(acquirerAck("R2").items().get(0).reason()).startsWith("ALREADY_REPRESENTED");
        assertThat(acquirerAck("R3").items().get(0).reason()).startsWith("NO_SUCH_CHARGEBACK");
        assertThat(acquirerAck("R4").items().get(0).reason()).startsWith("NO_SUCH_CHARGEBACK");
    }

    // ---- files refused whole ----

    private void assertRefused(Path ackFolder, String name, String reasonStart) {
        assertThat(sql("select status from net_dispute_files where file_name = '" + name + ".dsp'")).isEqualTo("REJECTED");
        ClearingAck ack = codec.readAck(ackFolder.resolve(name + ".ack"));
        assertThat(ack.status()).isEqualTo(ClearingAck.Status.REJECTED);
        assertThat(ack.reason()).startsWith(reasonStart);
        assertThat(sql("select count(*) from net_clearing_items where dispute_file is not null")).as("a refused file records nothing").isEqualTo("0");
    }

    @Test
    void filesThatCannotBeTrustedAreRefusedWholeAndAnsweredInTheSendersFolder() throws Exception {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("10.00")));
        ClearingFileCodec other = ClearingFileCodec.withSecret("someone else");
        other.write(dirs.issuerOut(), "D1.dsp", new ClearingFile("D1", DAY, "999900", "NET", "USD", List.of(chargeback(PAN, 1, de4("5.00"), "OTHER"))));
        Files.writeString(dirs.issuerOut().resolve("D2.dsp"), "hello", StandardCharsets.US_ASCII);
        chargebacks("D3", DAY, "999900", chargeback(PAN, 1, de4("5.00"), "OTHER"));
        Path damaged = dirs.issuerOut().resolve("D3.dsp");
        byte[] bytes = Files.readAllBytes(damaged);
        bytes[bytes.length / 2] ^= 1;
        Files.write(damaged, bytes);
        codec.write(dirs.issuerOut(), "D4.dsp", new ClearingFile("D4", DAY, "777700", "NET", "USD", List.of(chargeback(PAN, 1, de4("5.00"), "OTHER"))));
        codec.write(dirs.issuerOut(), "D5.dsp", new ClearingFile("D5", DAY, "999900", "OTHERNET", "USD", List.of(chargeback(PAN, 1, de4("5.00"), "OTHER"))));
        codec.write(dirs.issuerOut(), "D6.dsp", new ClearingFile("D9", DAY, "999900", "NET", "USD", List.of(chargeback(PAN, 1, de4("5.00"), "OTHER"))));
        codec.write(dirs.issuerOut(), "D7.dsp", new ClearingFile("D7", DAY, "999900", "NET", "USD", List.of(representment(PAN, 1, de4("5.00")))));
        codec.write(dirs.acquirerOut(), "R1.dsp", new ClearingFile("R1", DAY, "123456", "NET", "USD", List.of(chargeback(PAN, 1, de4("5.00"), "OTHER"))));
        codec.write(dirs.acquirerOut(), "R2.dsp", new ClearingFile("R2", DAY, "555555", "NET", "USD", List.of(representment(PAN, 1, de4("5.00")))));

        beat();

        assertRefused(dirs.issuerIn(), "D1", "DECRYPT_FAILED");
        assertRefused(dirs.issuerIn(), "D2", "NOT_A_CLEARING_FILE");
        assertRefused(dirs.issuerIn(), "D3", "DECRYPT_FAILED");
        assertRefused(dirs.issuerIn(), "D4", "UNKNOWN_SENDER");
        assertRefused(dirs.issuerIn(), "D5", "WRONG_RECEIVER");
        assertRefused(dirs.issuerIn(), "D6", "FILE_NAME_MISMATCH");
        assertRefused(dirs.issuerIn(), "D7", "WRONG_DIRECTION");
        assertRefused(dirs.acquirerIn(), "R1", "WRONG_DIRECTION");
        assertRefused(dirs.acquirerIn(), "R2", "UNKNOWN_SENDER");
        assertThat(list(dirs.acquirerIn(), ".dsp")).isEmpty();
        assertThat(list(dirs.issuerIn(), ".dsp")).isEmpty();
    }

    @Test
    void aRefusedOrProcessedFileIsNotLookedAtAgainAndCrashesAreRepaired() throws Exception {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("10.00")));
        chargebacks("D1", DAY, "999900", chargeback(PAN, 1, de4("10.00"), "OTHER"));
        beat();
        Path forward = list(dirs.acquirerIn(), ".dsp").get(0);
        ClearingFile before = codec.read(forward);
        byte[] ackBefore = Files.readAllBytes(dirs.issuerIn().resolve("D1.ack"));

        assertThat(disputes.tick()).as("nothing more to do").isZero();
        assertThat(Files.readAllBytes(dirs.issuerIn().resolve("D1.ack"))).isEqualTo(ackBefore);

        Files.delete(forward);
        Files.delete(dirs.issuerIn().resolve("D1.ack"));
        update("update net_dispute_forwards set written = false");
        update("update net_dispute_files set ack_written = false");
        assertThat(disputes.tick()).isEqualTo(2);

        assertThat(codec.read(forward)).isEqualTo(before);
        assertThat(issuerAck("D1").items()).extracting(ClearingAck.Item::result).containsExactly("CHARGEBACK_ACCEPTED");
        assertThat(sql("select count(*) from net_clearing_items where kind = 'CHARGEBACK'")).isEqualTo("1");
        // a restarted processor continues without redoing anything
        disputes = new DisputeProcessor(config, ds, vault, clock);
        assertThat(disputes.tick()).isZero();
    }

    @Test
    void twoProcessorsLookingAtTheSameFileRecordItOnce() throws Exception {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("10.00")), purchase(PAN, 2, de4("10.00")));
        chargebacks("D1", DAY, "999900", chargeback(PAN, 1, de4("10.00"), "OTHER"), chargeback(PAN, 2, de4("10.00"), "OTHER"));
        DisputeProcessor second = new DisputeProcessor(config, ds, vault, clock);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Throwable> failures = new ArrayList<>();
        Thread a = new Thread(() -> race(barrier, disputes, failures));
        Thread b = new Thread(() -> race(barrier, second, failures));
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(failures).isEmpty();
        beat();
        assertThat(sql("select count(*) from net_dispute_files where file_name = 'D1.dsp' and status = 'ACCEPTED'")).isEqualTo("1");
        assertThat(sql("select count(*) from net_clearing_items where kind = 'CHARGEBACK'")).isEqualTo("2");
        assertThat(sql("select count(*) from net_dispute_forwards")).isEqualTo("1");
    }

    private static void race(CyclicBarrier barrier, DisputeProcessor p, List<Throwable> failures) {
        try {
            barrier.await();
            p.tick();
        } catch (Throwable t) {
            synchronized (failures) {
                failures.add(t);
            }
        }
    }

    // ---- what the other side says about a forwarded file ----

    @Test
    void theAnswerOfTheReceivingSideIsRecordedAndAnUnusableAnswerIsMarkedInvalid() throws Exception {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("10.00")), purchase(PAN, 2, de4("10.00")), purchase(PAN, 3, de4("10.00")));
        chargebacks("D1", DAY, "999900", chargeback(PAN, 1, de4("1.00"), "OTHER"));
        chargebacks("D2", DAY, "999900", chargeback(PAN, 2, de4("1.00"), "OTHER"));
        chargebacks("D3", DAY, "999900", chargeback(PAN, 3, de4("1.00"), "OTHER"));
        beat();
        assertThat(sql("select count(*) from net_dispute_forwards where ack_status is null")).as("nobody answered yet").isEqualTo("3");
        codec.writeAck(dirs.acquirerOut(), "X0000001.ack", ClearingAck.accepted("X0000001", List.of(new ClearingAck.Item(1, "CHARGEBACK_ACCEPTED", ""))));
        codec.writeAck(dirs.acquirerOut(), "X0000002.ack", ClearingAck.rejected("X0000002", "UNKNOWN_PAYMENT: not ours"));
        codec.writeAck(dirs.acquirerOut(), "X0000003.ack", ClearingAck.accepted("SOMETHING-ELSE", List.of()));

        beat();

        assertThat(sql("select ack_status from net_dispute_forwards where file_name = 'X0000001.dsp'")).isEqualTo("ACCEPTED");
        assertThat(sql("select ack_status || '/' || ack_reason from net_dispute_forwards where file_name = 'X0000002.dsp'")).isEqualTo("REJECTED/UNKNOWN_PAYMENT: not ours");
        assertThat(sql("select ack_status from net_dispute_forwards where file_name = 'X0000003.dsp'")).isEqualTo("INVALID");
        Files.writeString(dirs.acquirerOut().resolve("X0000004.ack"), "garbage", StandardCharsets.US_ASCII);
    }

    // ---- settlement counts them ----

    @Test
    void aChargebackIsRefundLikeAndARepresentmentPurchaseLikeInTheSettlementAndTheNetsAddUpToZero() {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("100.00")));
        chargedBack(1, "40.00");
        representments("R1", DAY, "123456", representment(PAN, 1, de4("25.00")));
        beat();

        SettlementService.Run run = settlement.run(DAY).orElseThrow();

        // purchases 100 + 25 (the representment), refunds 40 (the chargeback); interchange 1.20 - 0.48 + 0.30 = 1.02; scheme fee 0.05 - 0.02 + 0.01 = 0.04
        SettlementFiles.Line issuerLine = run.lines().stream().filter(l -> l.role().equals("ISSUER")).findFirst().orElseThrow();
        assertThat(issuerLine.purchases()).isEqualByComparingTo("125.00");
        assertThat(issuerLine.refunds()).isEqualByComparingTo("40.00");
        assertThat(issuerLine.interchange()).isEqualByComparingTo("1.02");
        assertThat(issuerLine.schemeFee()).isEqualByComparingTo("0.04");
        assertThat(issuerLine.net()).isEqualByComparingTo("-84.02");
        assertThat(issuerLine.items()).isEqualTo(3);
        assertThat(run.lines().stream().map(SettlementFiles.Line::net).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
        assertThat(run.itemCount()).isEqualTo(3);
        assertThat(settlement.run(DAY)).as("settled once").isEmpty();
    }

    @Test
    void aRefusedDisputeItemIsNeverSettled() {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("100.00")));
        chargebacks("D1", DAY, "999900", chargeback(PAN, 9, de4("5.00"), "OTHER"));
        beat();

        SettlementService.Run run = settlement.run(DAY).orElseThrow();

        assertThat(run.itemCount()).as("only the purchase").isEqualTo(1);
    }

    // ---- secrets ----

    @Test
    void noCardNumberIsKeptInTheDisputeTablesOrFiles() throws Exception {
        clear("B1", DAY, "123456", purchase(PAN, 1, de4("10.00")));
        chargedBack(1, "10.00");
        representments("R1", DAY, "123456", representment(PAN, 1, de4("10.00")));
        beat();

        for (String table : List.of("net_dispute_files", "net_dispute_forwards", "net_clearing_items")) {
            assertThat(allText(table)).as(table).doesNotContain(PAN);
        }
        try (var all = Files.walk(root)) {
            for (Path p : all.filter(Files::isRegularFile).toList()) {
                assertThat(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1)).as(p.toString()).doesNotContain(PAN).doesNotContain("FILEHDR").doesNotContain("signed receipt");
            }
        }
    }

    // ---- helpers ----

    private static List<Path> list(Path dir, String suffix) {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
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

    private String allText(String table) {
        StringBuilder out = new StringBuilder();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select t::text from " + table + " t"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.append(rs.getString(1)).append('\n');
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }
}
