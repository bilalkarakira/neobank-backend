package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.acquirer.AcquirerService.BadRequestException;
import com.bilal.cardnet.acquirer.AcquirerService.ConflictException;
import com.bilal.cardnet.acquirer.AcquirerService.NotFoundException;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.shared.ClearingDirs;
import com.bilal.cardnet.shared.testing.FakeIssuerClearing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static com.bilal.cardnet.acquirer.AcqHarness.PAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Capture and clearing at the acquirer, over real files, a real network and a fake issuer. */
class AcquirerClearingTest {

    @TempDir
    Path root;

    final ClearingFileCodec codec = ClearingFileCodec.withSecret("acq-test-secret");

    private AcqHarness harness() throws Exception {
        return new AcqHarness(root, c -> { }, n -> { });
    }

    private List<Path> files(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".clr")).sorted().toList();
        }
    }

    private ClearingFile onlyBatchFile(AcqHarness h) throws Exception {
        assertThat(files(h.dirs.acquirerOut())).hasSize(1);
        return codec.read(files(h.dirs.acquirerOut()).get(0));
    }

    // ---- closing the day ----

    @Test
    void closeDayPutsExactlyTheApprovedPaymentsInOneFileAndTheyClearWhenTheIssuerPosts() throws Exception {
        try (AcqHarness h = harness()) {
            UUID a = h.payApproved("50.00");
            UUID b = h.payApproved("12.50");
            h.issuer.decision = m -> "55";
            h.service().pay(h.payWithPin("9.99"));                  // declined
            h.issuer.decision = m -> "00";
            UUID voided = h.payApproved("7.00");
            h.service().voidSale(voided);

            AcquirerClearing.BatchResult batch = h.acquirer.clearing().closeDay().orElseThrow();

            assertThat(batch.count()).isEqualTo(2);
            assertThat(batch.controlTotal()).isEqualTo(6250);
            assertThat(h.status(a)).isEqualTo("BATCHED");
            assertThat(h.status(b)).isEqualTo("BATCHED");
            ClearingFile file = onlyBatchFile(h);
            assertThat(file.fileId()).isEqualTo(batch.fileId()).startsWith("ACQ");
            assertThat(file.sender()).isEqualTo("123456");
            assertThat(file.receiver()).isEqualTo("NET");
            assertThat(file.presentments()).hasSize(2);
            Message first = file.presentments().get(0);
            Message original = h.acquirer.service().store().request(a);
            assertThat(first.mti()).isEqualTo("1240");
            assertThat(first.get(2)).isEqualTo(PAN);
            assertThat(first.get(3)).isEqualTo("000000");
            assertThat(first.get(4)).isEqualTo("000000005000");
            assertThat(first.get(38)).isEqualTo(h.service().store().find(a).orElseThrow().authCode());
            for (int de : new int[] {7, 11, 12, 13, 14, 18, 32, 37, 41, 42, 43, 49}) {
                assertThat(first.get(de)).as("DE %d", de).isEqualTo(original.get(de));
            }
            assertThat(file.presentments().get(1).get(4)).isEqualTo("000000001250");

            h.pumpUntil("both payments cleared", () -> h.status(a).equals("CLEARED") && h.status(b).equals("CLEARED"));
            assertThat(h.scalar("select status from acq_batches")).isEqualTo("ACCEPTED");
            assertThat(h.status(voided)).isIn("REVERSING", "REVERSED");
            assertThat(h.clearingIssuer.received).hasSize(1);
            assertThat(h.clearingIssuer.received.get(0).presentments()).hasSize(2);
        }
    }

    @Test
    void anEmptyDayMakesNoFile() throws Exception {
        try (AcqHarness h = harness()) {
            assertThat(h.acquirer.clearing().closeDay()).isEmpty();
            h.payApproved("5.00");
            h.issuer.decision = m -> "05";
            h.service().pay(h.pay("6.00"));
            assertThat(h.acquirer.clearing().closeDay()).isPresent();
            assertThat(h.acquirer.clearing().closeDay()).as("nothing new since").isEmpty();
            assertThat(files(h.dirs.acquirerOut())).hasSize(1);
            assertThat(h.count("select count(*) from acq_batches")).isEqualTo(1);
        }
    }

    @Test
    void aPaymentNeverGoesIntoTwoFilesEvenWhenTwoCloseTheDayAtOnce() throws Exception {
        try (AcqHarness h = harness()) {
            for (int i = 0; i < 6; i++) {
                h.payApproved("1.0" + i);
            }
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Optional<AcquirerClearing.BatchResult>> results = java.util.Collections.synchronizedList(new ArrayList<>());
            Runnable close = () -> {
                try {
                    barrier.await();
                    results.add(h.acquirer.clearing().closeDay());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            Thread t1 = new Thread(close);
            Thread t2 = new Thread(close);
            t1.start();
            t2.start();
            t1.join();
            t2.join();

            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(files(h.dirs.acquirerOut())).hasSize(1);
            assertThat(onlyBatchFile(h).presentments()).hasSize(6);
            assertThat(h.count("select count(*) from acq_transactions where status = 'BATCHED'")).isEqualTo(6);
        }
    }

    @Test
    void aVoidAfterTheBatchIsRefusedAndAVoidBeforeKeepsThePaymentOutOfIt() throws Exception {
        try (AcqHarness h = harness()) {
            UUID kept = h.payApproved("5.00");
            UUID gone = h.payApproved("6.00");
            h.service().voidSale(gone);
            h.acquirer.clearing().closeDay();

            assertThat(onlyBatchFile(h).presentments()).hasSize(1);
            assertThatThrownBy(() -> h.service().voidSale(kept)).isInstanceOf(ConflictException.class).hasMessageContaining("batched");
        }
    }

    @Test
    void withoutAClearingFolderClosingTheDayIsRefused() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            assertThat(h.acquirer.clearing().enabled()).isFalse();
            assertThatThrownBy(() -> h.acquirer.clearing().closeDay()).isInstanceOf(ConflictException.class).hasMessageContaining("switched off");
            assertThat(h.acquirer.clearing().processAcknowledgements()).isZero();
        }
    }

    // ---- the tip ----

    @Test
    void aTipChangesOnlyTheAmountInThePresentment() throws Exception {
        try (AcqHarness h = harness()) {
            UUID id = h.payApproved("50.00");

            AcquirerStore.Row row = h.acquirer.clearing().adjust(id, "57.50");
            assertThat(row.finalAmount()).isEqualByComparingTo("57.50");
            assertThat(row.amount()).as("what was authorized does not change").isEqualByComparingTo("50.00");
            assertThat(row.status()).isEqualTo("APPROVED");

            AcquirerClearing.BatchResult batch = h.acquirer.clearing().closeDay().orElseThrow();

            assertThat(batch.controlTotal()).isEqualTo(5750);
            assertThat(onlyBatchFile(h).presentments().get(0).get(4)).isEqualTo("000000005750");
        }
    }

    @Test
    void theTipLimitIsExactToTheCent() throws Exception {
        try (AcqHarness h = harness()) {
            UUID fifty = h.payApproved("50.00");
            assertThat(h.acquirer.clearing().adjust(fifty, "60.00").finalAmount()).isEqualByComparingTo("60.00");
            assertThatThrownBy(() -> h.acquirer.clearing().adjust(fifty, "60.01")).isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("at most 60.00").hasMessageContaining("20%");

            UUID odd = h.payApproved("33.33");   // 20% is 39.996: the limit rounds DOWN so it is never exceeded
            assertThat(h.acquirer.clearing().adjust(odd, "39.99").finalAmount()).isEqualByComparingTo("39.99");
            assertThatThrownBy(() -> h.acquirer.clearing().adjust(odd, "40.00")).isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void anAmountCanAlsoGoDownOrStayAndCanBeChangedAgainBeforeTheBatch() throws Exception {
        try (AcqHarness h = harness()) {
            UUID id = h.payApproved("50.00");
            assertThat(h.acquirer.clearing().adjust(id, "20.00").finalAmount()).isEqualByComparingTo("20.00");
            assertThat(h.acquirer.clearing().adjust(id, "50.00").finalAmount()).isEqualByComparingTo("50.00");
            assertThat(h.acquirer.clearing().adjust(id, "55.00").finalAmount()).isEqualByComparingTo("55.00");
            h.acquirer.clearing().closeDay();
            assertThat(onlyBatchFile(h).presentments().get(0).get(4)).isEqualTo("000000005500");
        }
    }

    @Test
    void badTipRequestsAreRefused() throws Exception {
        try (AcqHarness h = harness()) {
            UUID id = h.payApproved("50.00");
            for (String bad : new String[] {"0", "0.00", "-5", "abc", "", " ", "1.234", "12345678", "1e3", "5,00"}) {
                assertThatThrownBy(() -> h.acquirer.clearing().adjust(id, bad)).as(bad).isInstanceOf(BadRequestException.class);
            }
            assertThatThrownBy(() -> h.acquirer.clearing().adjust(id, null)).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> h.acquirer.clearing().adjust(UUID.randomUUID(), "5.00")).isInstanceOf(NotFoundException.class);
            assertThat(h.service().store().find(id).orElseThrow().finalAmount()).isNull();
        }
    }

    @Test
    void onlyAnApprovedUnbatchedPaymentCanGetATip() throws Exception {
        try (AcqHarness h = harness()) {
            UUID batched = h.payApproved("10.00");
            h.acquirer.clearing().closeDay();
            assertThatThrownBy(() -> h.acquirer.clearing().adjust(batched, "11.00")).isInstanceOf(ConflictException.class).hasMessageContaining("batched");

            h.issuer.decision = m -> "05";
            UUID declined = h.service().pay(h.pay("10.00")).id();
            assertThatThrownBy(() -> h.acquirer.clearing().adjust(declined, "11.00")).isInstanceOf(ConflictException.class).hasMessageContaining("declined");

            h.pumpUntil("cleared", () -> h.status(batched).equals("CLEARED"));
            assertThatThrownBy(() -> h.acquirer.clearing().adjust(batched, "11.00")).isInstanceOf(ConflictException.class).hasMessageContaining("cleared");
        }
    }

    // ---- refunds ----

    private UUID clearedPurchase(AcqHarness h, String amount) {
        UUID id = h.payApproved(amount);
        h.acquirer.clearing().closeDay();
        h.pumpUntil("the purchase cleared", () -> h.status(id).equals("CLEARED"));
        return id;
    }

    @Test
    void aRefundIsOwnItemInTheNextBatchAndClearsAsRefundPosted() throws Exception {
        try (AcqHarness h = harness()) {
            UUID purchase = clearedPurchase(h, "50.00");

            AcquirerStore.Row refund = h.acquirer.clearing().refund(purchase, "20.00");

            assertThat(refund.kind()).isEqualTo("REFUND");
            assertThat(refund.status()).isEqualTo("REFUND_DUE");
            assertThat(refund.refundOf()).isEqualTo(purchase);
            assertThat(refund.amount()).isEqualByComparingTo("20.00");
            h.acquirer.clearing().closeDay();

            Message m = files(h.dirs.acquirerOut()).stream().map(p -> codec.read(p)).filter(f -> f.presentments().get(0).get(3).equals("200000"))
                    .findFirst().orElseThrow().presentments().get(0);
            Message original = h.service().store().request(purchase);
            assertThat(m.get(4)).isEqualTo("000000002000");
            assertThat(m.has(38)).as("a refund has no authorization").isFalse();
            assertThat(m.get(2)).isEqualTo(PAN);
            assertThat(m.get(11)).as("own STAN").isNotEqualTo(original.get(11));
            assertThat(m.get(37)).as("own RRN").isNotEqualTo(original.get(37));
            assertThat(m.get(32)).isEqualTo(original.get(32));
            h.pumpUntil("the refund cleared", () -> h.status(refund.id()).equals("CLEARED"));
            assertThat(h.scalar("select reason from acq_transactions where id = ?", refund.id())).isEqualTo("REFUND_POSTED");
        }
    }

    @Test
    void refundsAddUpAndNeverExceedWhatCleared() throws Exception {
        try (AcqHarness h = harness()) {
            UUID purchase = clearedPurchase(h, "50.00");
            h.acquirer.clearing().refund(purchase, "20.00");
            h.acquirer.clearing().refund(purchase, "29.99");

            assertThatThrownBy(() -> h.acquirer.clearing().refund(purchase, "0.02")).isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Only 0.01 of the 50.00");
            assertThat(h.acquirer.clearing().refund(purchase, "0.01").amount()).isEqualByComparingTo("0.01");
            assertThatThrownBy(() -> h.acquirer.clearing().refund(purchase, "0.01")).isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Only 0.00");
        }
    }

    @Test
    void theRefundLimitIsTheClearedAmountNotTheAuthorizedOne() throws Exception {
        try (AcqHarness h = harness()) {
            UUID id = h.payApproved("50.00");
            h.acquirer.clearing().adjust(id, "57.50");
            h.acquirer.clearing().closeDay();
            h.pumpUntil("cleared", () -> h.status(id).equals("CLEARED"));

            assertThatThrownBy(() -> h.acquirer.clearing().refund(id, "57.51")).isInstanceOf(BadRequestException.class);
            assertThat(h.acquirer.clearing().refund(id, "57.50").amount()).isEqualByComparingTo("57.50");
        }
    }

    @Test
    void onlyAClearedPurchaseCanBeRefunded() throws Exception {
        try (AcqHarness h = harness()) {
            UUID approved = h.payApproved("10.00");
            assertThatThrownBy(() -> h.acquirer.clearing().refund(approved, "5.00")).isInstanceOf(ConflictException.class).hasMessageContaining("approved");
            h.issuer.decision = m -> "05";
            UUID declined = h.service().pay(h.pay("10.00")).id();
            assertThatThrownBy(() -> h.acquirer.clearing().refund(declined, "5.00")).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> h.acquirer.clearing().refund(UUID.randomUUID(), "5.00")).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> h.acquirer.clearing().refund(approved, "0")).isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void aRefundOfARefundIsRefusedAndAResendNeverSendsARefund() throws Exception {
        try (AcqHarness h = harness()) {
            UUID purchase = clearedPurchase(h, "50.00");
            AcquirerStore.Row refund = h.acquirer.clearing().refund(purchase, "5.00");
            h.acquirer.clearing().closeDay();
            h.pumpUntil("the refund cleared", () -> h.status(refund.id()).equals("CLEARED"));

            assertThatThrownBy(() -> h.acquirer.clearing().refund(refund.id(), "1.00")).isInstanceOf(ConflictException.class);
            assertThat(h.service().store().latest().orElseThrow().id()).as("the terminal can only send payments again").isEqualTo(purchase);
            assertThat(h.service().resendLast().message()).contains("The same answer again");
        }
    }

    @Test
    void twoRefundsAtOnceCannotTogetherExceedTheAmount() throws Exception {
        try (AcqHarness h = harness()) {
            UUID purchase = clearedPurchase(h, "50.00");
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Object> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
            Runnable refund = () -> {
                try {
                    barrier.await();
                    outcomes.add(h.acquirer.clearing().refund(purchase, "30.00"));
                } catch (BadRequestException e) {
                    outcomes.add(e);
                } catch (Exception e) {
                    outcomes.add(e);
                }
            };
            Thread t1 = new Thread(refund);
            Thread t2 = new Thread(refund);
            t1.start();
            t2.start();
            t1.join();
            t2.join();

            assertThat(outcomes.stream().filter(o -> o instanceof AcquirerStore.Row)).hasSize(1);
            assertThat(outcomes.stream().filter(o -> o instanceof BadRequestException)).hasSize(1);
            assertThat(h.count("select count(*) from acq_transactions where refund_of = ?", purchase)).isEqualTo(1);
        }
    }

    @Test
    void aRefundTheIssuerRejectsFreesItsAmountForANewOne() throws Exception {
        try (AcqHarness h = harness()) {
            UUID purchase = clearedPurchase(h, "50.00");
            AcquirerStore.Row refund = h.acquirer.clearing().refund(purchase, "50.00");
            h.clearingIssuer.decision = m -> new FakeIssuerClearing.Verdict("REJECTED", "card closed");
            h.acquirer.clearing().closeDay();
            h.pumpUntil("rejected", () -> h.status(refund.id()).equals("CLEARING_REJECTED"));

            assertThat(h.scalar("select reason from acq_transactions where id = ?", refund.id())).isEqualTo("REJECTED: card closed");
            assertThat(h.acquirer.clearing().refund(purchase, "50.00").status()).isEqualTo("REFUND_DUE");
        }
    }

    // ---- the network's answers ----

    @Test
    void aFileTheNetworkRefusesLeavesEveryPaymentReadyForTheNextFile() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> c.acquirerId = "654321", n -> { })) {
            UUID a = h.payApproved("5.00");
            UUID b = h.payApproved("6.00");
            h.acquirer.clearing().adjust(a, "5.50");

            h.acquirer.clearing().closeDay();
            h.pumpUntil("the batch was refused", () -> h.scalar("select status from acq_batches").equals("REJECTED"));

            assertThat(h.scalar("select ack_reason from acq_batches")).contains("UNKNOWN_SENDER");
            assertThat(h.status(a)).isEqualTo("APPROVED");
            assertThat(h.status(b)).isEqualTo("APPROVED");
            assertThat(h.scalar("select batch_id from acq_transactions where id = ?", a)).isNull();
            assertThat(h.scalar("select reason from acq_transactions where id = ?", a)).contains("the network refused the file");
            assertThat(h.service().store().find(a).orElseThrow().finalAmount()).as("the tip is kept").isEqualByComparingTo("5.50");

            h.networkConfig.knownAcquirers = Set.of("654321");   // the problem is fixed
            h.acquirer.clearing().closeDay();
            h.pumpUntil("cleared", () -> h.status(a).equals("CLEARED") && h.status(b).equals("CLEARED"));
            assertThat(h.count("select count(*) from acq_batches where status = 'ACCEPTED'")).isEqualTo(1);
        }
    }

    @Test
    void aRefundInAFileTheNetworkRefusesIsDueAgainAndStaysARefund() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> { }, n -> { })) {
            UUID purchase = h.payApproved("50.00");
            h.acquirer.clearing().closeDay();
            h.pumpUntil("cleared", () -> h.status(purchase).equals("CLEARED"));
            AcquirerStore.Row refund = h.acquirer.clearing().refund(purchase, "10.00");
            h.networkConfig.knownAcquirers = Set.of("somebody-else");

            h.acquirer.clearing().closeDay();
            h.pumpUntil("the batch was refused", () -> h.count("select count(*) from acq_batches where status = 'REJECTED'") == 1);

            assertThat(h.status(refund.id())).isEqualTo("REFUND_DUE");
            assertThat(h.scalar("select kind from acq_transactions where id = ?", refund.id())).isEqualTo("REFUND");
            h.networkConfig.knownAcquirers = Set.of("123456");
            h.acquirer.clearing().closeDay();
            h.pumpUntil("the refund cleared", () -> h.status(refund.id()).equals("CLEARED"));
        }
    }

    @Test
    void anItemTheIssuerRejectsIsMarkedWithItsReasonAndTheOthersClear() throws Exception {
        try (AcqHarness h = harness()) {
            UUID ok = h.payApproved("5.00");
            UUID bad = h.payApproved("6.00");
            UUID forced = h.payApproved("7.00");
            UUID dup = h.payApproved("8.00");
            h.clearingIssuer.decision = m -> switch (m.get(4)) {
                case "000000000600" -> new FakeIssuerClearing.Verdict("REJECTED", "the authorization was reversed");
                case "000000000700" -> new FakeIssuerClearing.Verdict("FORCE_POSTED", "unknown authorization");
                case "000000000800" -> new FakeIssuerClearing.Verdict("DUPLICATE", "already captured");
                default -> new FakeIssuerClearing.Verdict("POSTED", "");
            };

            h.acquirer.clearing().closeDay();
            h.pumpUntil("settled", () -> h.status(bad).equals("CLEARING_REJECTED"));

            assertThat(h.status(ok)).isEqualTo("CLEARED");
            assertThat(h.status(forced)).isEqualTo("CLEARED");
            assertThat(h.status(dup)).isEqualTo("CLEARED");
            assertThat(h.scalar("select reason from acq_transactions where id = ?", bad)).isEqualTo("REJECTED: the authorization was reversed");
            assertThat(h.scalar("select reason from acq_transactions where id = ?", forced)).isEqualTo("FORCE_POSTED");
            assertThat(h.scalar("select status from acq_batches")).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void anAcknowledgementThatLeavesAPaymentOutMarksItRejected() throws Exception {
        try (AcqHarness h = harness()) {
            UUID a = h.payApproved("5.00");
            UUID b = h.payApproved("6.00");
            AcquirerClearing.BatchResult batch = h.acquirer.clearing().closeDay().orElseThrow();
            codec.writeAck(h.dirs.acquirerIn(), ClearingDirs.ackName(batch.fileName()),
                    ClearingAck.accepted(batch.fileId(), List.of(new ClearingAck.Item(1, "POSTED", ""))));

            h.acquirer.clearing().processAcknowledgements();

            assertThat(h.status(a)).isEqualTo("CLEARED");
            assertThat(h.status(b)).isEqualTo("CLEARING_REJECTED");
            assertThat(h.scalar("select reason from acq_transactions where id = ?", b)).contains("no result");
        }
    }

    @Test
    void anUnreadableOrForeignAcknowledgementIsNotTrustedAndTheBatchWaits() throws Exception {
        try (AcqHarness h = harness()) {
            UUID a = h.payApproved("5.00");
            AcquirerClearing.BatchResult batch = h.acquirer.clearing().closeDay().orElseThrow();
            Path ack = h.dirs.acquirerIn().resolve(ClearingDirs.ackName(batch.fileName()));

            Files.writeString(ack, "garbage", StandardCharsets.US_ASCII);
            assertThat(h.acquirer.clearing().processAcknowledgements()).isZero();
            assertThat(h.scalar("select status from acq_batches")).isEqualTo("SUBMITTED");
            assertThat(h.scalar("select ack_reason from acq_batches")).contains("unreadable").contains("NOT_A_CLEARING_FILE");

            codec.writeAck(h.dirs.acquirerIn(), ClearingDirs.ackName(batch.fileName()), ClearingAck.accepted("SOMEONE-ELSE", List.of(new ClearingAck.Item(1, "POSTED", ""))));
            assertThat(h.acquirer.clearing().processAcknowledgements()).isZero();
            assertThat(h.status(a)).isEqualTo("BATCHED");

            Files.delete(ack);
            h.pumpUntil("the real acknowledgement", () -> h.status(a).equals("CLEARED"));
        }
    }

    @Test
    void anAcknowledgementIsAppliedOnceEvenWhenReadTwice() throws Exception {
        try (AcqHarness h = harness()) {
            UUID a = h.payApproved("5.00");
            h.acquirer.clearing().closeDay();
            h.pumpUntil("cleared", () -> h.status(a).equals("CLEARED"));
            String before = h.scalar("select updated_at from acq_transactions where id = ?", a);

            assertThat(h.acquirer.clearing().processAcknowledgements()).isZero();
            assertThat(h.scalar("select updated_at from acq_transactions where id = ?", a)).isEqualTo(before);
        }
    }

    // ---- crashes ----

    @Test
    void aBatchWhoseFileWasNeverWrittenGetsItAtRestartWithTheSameName() throws Exception {
        try (AcqHarness h = harness()) {
            UUID a = h.payApproved("5.00");
            AcquirerClearing.BatchResult batch = h.acquirer.clearing().closeDay().orElseThrow();
            ClearingFile original = onlyBatchFile(h);
            Files.delete(h.dirs.acquirerOut().resolve(batch.fileName()));
            try (var c = h.ds.getConnection(); var st = c.createStatement()) {
                st.executeUpdate("update acq_batches set status = 'CREATED', submitted_at = null");
            }

            h.restartAcquirer();

            assertThat(onlyBatchFile(h)).isEqualTo(original);
            assertThat(h.scalar("select status from acq_batches")).isEqualTo("SUBMITTED");
            h.pumpUntil("cleared", () -> h.status(a).equals("CLEARED"));
        }
    }

    @Test
    void aRestartedAcquirerStillSeesTheAcknowledgement() throws Exception {
        try (AcqHarness h = harness()) {
            UUID a = h.payApproved("5.00");
            h.acquirer.clearing().closeDay();
            h.network.clearing().tick();
            h.clearingIssuer.tick();
            h.network.clearing().tick();

            h.restartAcquirer();
            h.acquirer.clearing().processAcknowledgements();

            assertThat(h.status(a)).isEqualTo("CLEARED");
        }
    }

    // ---- the background beat and secrets ----

    @Test
    void theBackgroundBeatsMoveFilesWithoutHelp() throws Exception {
        try (AcqHarness h = new AcqHarness(root, c -> c.clearingPoll = java.time.Duration.ofMillis(50),
                n -> n.clearingPoll = java.time.Duration.ofMillis(50))) {
            UUID a = h.payApproved("5.00");
            h.acquirer.clearing().closeDay();

            com.bilal.cardnet.shared.testing.Await.until("cleared by the background beats", () -> {
                h.clearingIssuer.tick();
                return h.status(a).equals("CLEARED");
            });
        }
    }

    @Test
    void noCardNumberOrPinIsKeptOrWrittenInTheClear() throws Exception {
        try (AcqHarness h = harness()) {
            UUID a = h.payApproved("5.00");
            h.service().pay(h.payWithPin("6.00"));
            h.acquirer.clearing().closeDay();
            h.pumpUntil("cleared", () -> h.status(a).equals("CLEARED"));
            h.acquirer.clearing().refund(a, "1.00");
            h.acquirer.clearing().closeDay();
            h.pumpUntil("all settled", () -> h.count("select count(*) from acq_batches where status = 'ACCEPTED'") == 2);

            for (String table : List.of("acq_transactions", "acq_batches")) {
                try (var c = h.ds.getConnection(); var ps = c.prepareStatement("select * from " + table); var rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    while (rs.next()) {
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            // timestamps are left out: their microseconds can contain any four digits, the PIN included, by chance
                            if (!meta.getColumnTypeName(i).equals("bytea") && !meta.getColumnTypeName(i).startsWith("timestamp") && rs.getString(i) != null) {
                                assertThat(rs.getString(i)).as(table + "." + meta.getColumnName(i)).doesNotContain(PAN).doesNotContain(AcqHarness.PIN);
                            }
                        }
                    }
                }
            }
            try (var all = Files.walk(root)) {
                for (Path p : all.filter(Files::isRegularFile).toList()) {
                    String content = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                    assertThat(content).as(p.toString()).doesNotContain(PAN).doesNotContain("FILEHDR").doesNotContain("ACKHDR");
                }
            }
        }
    }
}
