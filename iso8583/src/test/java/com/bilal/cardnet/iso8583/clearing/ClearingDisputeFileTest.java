package com.bilal.cardnet.iso8583.clearing;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingFile.RecordKind;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException.Reason;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dispute files: chargebacks (1442) and representments (1240 with REP|...), their rules, and the .clr / .dsp separation. */
class ClearingDisputeFileTest {

    static final String PAN = "9999000000000012";
    static final String DSP = "D0000001.dsp";
    final ClearingFileCodec codec = ClearingFileCodec.withSecret("test-secret");

    static Message chargeback(int n, String amount, String reason, String note) {
        return Message.of("1442").set(2, PAN).set(3, "000000").set(4, amount).set(7, "1006120000").set(11, String.format("%06d", n))
                .set(12, "120000").set(13, "1006").set(32, "00000123456").set(37, String.format("%012d", n)).set(41, "TERM0001")
                .set(42, "MERCHANT000001 ").set(49, "840").set(48, "CB|" + reason + "|" + note);
    }

    static Message representment(int n, String amount, String note) {
        return Message.of("1240").set(2, PAN).set(3, "000000").set(4, amount).set(7, "1006130000").set(11, String.format("%06d", n))
                .set(12, "130000").set(13, "1006").set(32, "00000123456").set(37, String.format("%012d", n)).set(41, "TERM0001")
                .set(42, "MERCHANT000001 ").set(49, "840").set(48, "REP|" + note);
    }

    static ClearingFile file(Message... items) {
        return new ClearingFile("D0000001", LocalDate.of(2026, 10, 6), "999900", "NET", "USD", List.of(items));
    }

    // ---- round trips ----

    @Test
    void aDisputeFileRoundTripsWithChargebacksAndRepresentments() {
        ClearingFile in = file(chargeback(1, "000000002500", "UNAUTHORIZED", "I was abroad"), representment(2, "000000000900", "Signed receipt attached"));

        ClearingFile out = codec.decode(codec.encode(in, DSP), DSP);

        assertThat(out).isEqualTo(in);
        assertThat(out.presentments()).extracting(ClearingFile::kindOf).containsExactly(RecordKind.CHARGEBACK, RecordKind.REPRESENTMENT);
        assertThat(ClearingFile.reasonOf(out.presentments().get(0))).isEqualTo("UNAUTHORIZED");
        assertThat(ClearingFile.noteOf(out.presentments().get(0))).isEqualTo("I was abroad");
        assertThat(ClearingFile.noteOf(out.presentments().get(1))).isEqualTo("Signed receipt attached");
        assertThat(ClearingFile.reasonOf(out.presentments().get(1))).isNull();
        assertThat(out.controlTotal()).isEqualTo(3400);
    }

    @Test
    void everyReasonIsAccepted() {
        for (String reason : ClearingFile.DISPUTE_REASONS) {
            assertThat(codec.decode(codec.encode(file(chargeback(1, "000000000100", reason, "n")), DSP), DSP).presentments()).hasSize(1);
        }
        assertThat(ClearingFile.DISPUTE_REASONS).containsExactlyInAnyOrder("UNAUTHORIZED", "NOT_RECEIVED", "DUPLICATE", "WRONG_AMOUNT", "OTHER");
    }

    @Test
    void anEmptyNoteIsAllowedOnAChargebackAndRequiredOnARepresentment() {
        assertThat(codec.decode(codec.encode(file(chargeback(1, "000000000100", "OTHER", "")), DSP), DSP).presentments()).hasSize(1);
        assertThatThrownBy(() -> codec.encode(file(representment(1, "000000000100", "")), DSP))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_PRESENTMENT));
    }

    @Test
    void theNoteLimitIsExact() {
        String limit = "x".repeat(200);
        assertThat(codec.decode(codec.encode(file(chargeback(1, "000000000100", "OTHER", limit)), DSP), DSP)).isNotNull();
        assertThat(codec.decode(codec.encode(file(representment(1, "000000000100", limit)), DSP), DSP)).isNotNull();
        assertThatThrownBy(() -> codec.encode(file(chargeback(1, "000000000100", "OTHER", limit + "x")), DSP)).isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> codec.encode(file(representment(1, "000000000100", limit + "x")), DSP)).isInstanceOf(ClearingFileException.class);
    }

    // ---- the rules ----

    private static void refused(Message m, Reason reason) {
        ClearingFileCodec codec = ClearingFileCodec.withSecret("test-secret");
        assertThatThrownBy(() -> codec.encode(file(m), DSP)).as(m.toString()).isInstanceOfSatisfying(ClearingFileException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void aChargebackNeedsItsElementsItsReasonAndAPositiveAmount() {
        for (int missing : new int[] {2, 3, 4, 7, 11, 12, 13, 32, 37, 41, 42, 49, 48}) {
            Message m = chargeback(1, "000000000100", "OTHER", "n").remove(missing);
            refused(m, Reason.MISSING_ELEMENT);
        }
        refused(chargeback(1, "000000000100", "FRAUD", "n"), Reason.BAD_PRESENTMENT);
        refused(chargeback(1, "000000000100", "", "n"), Reason.BAD_PRESENTMENT);
        refused(chargeback(1, "000000000100", "OTHER", "a|b"), Reason.BAD_PRESENTMENT);
        refused(chargeback(1, "000000000000", "OTHER", "n"), Reason.BAD_PRESENTMENT);
        refused(chargeback(1, "000000000100", "OTHER", "n").set(3, "200000"), Reason.BAD_PRESENTMENT);
        refused(chargeback(1, "000000000100", "OTHER", "n").set(48, "CB|OTHER"), Reason.BAD_PRESENTMENT);
        refused(chargeback(1, "000000000100", "OTHER", "n").set(48, "XX|OTHER|n"), Reason.BAD_PRESENTMENT);
    }

    @Test
    void aRepresentmentNeedsItsElementsAndANote() {
        for (int missing : new int[] {2, 3, 4, 7, 11, 12, 13, 32, 37, 41, 42, 49}) {
            refused(representment(1, "000000000100", "n").remove(missing), Reason.MISSING_ELEMENT);
        }
        refused(representment(1, "000000000000", "n"), Reason.BAD_PRESENTMENT);
        refused(representment(1, "000000000100", "n").set(3, "200000"), Reason.BAD_PRESENTMENT);
        refused(representment(1, "000000000100", "a|b"), Reason.BAD_PRESENTMENT);
    }

    @Test
    void aFirstPresentmentMayNotCarryDe48() {
        // in a clearing file (a dispute file would refuse the purchase for another reason)
        assertThatThrownBy(() -> codec.encode(file(ClearingFileCodecTest.purchase(1, "000000000100").set(48, "something")), "B0001.clr"))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.BAD_PRESENTMENT);
                    assertThat(e.getMessage()).contains("no DE 48");
                });
        // text that merely STARTS like a representment tag is not a representment
        assertThatThrownBy(() -> codec.encode(file(ClearingFileCodecTest.purchase(1, "000000000100").set(48, "REPORT|x")), "B0001.clr"))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.getMessage()).contains("no DE 48"));
        assertThat(ClearingFile.kindOf(ClearingFileCodecTest.purchase(1, "000000000100").set(48, "REPORT|x"))).isEqualTo(RecordKind.PURCHASE);
    }

    @Test
    void aOneToOneMtiOtherThanTheTwoIsStillRefused() {
        refused(chargeback(1, "000000000100", "OTHER", "n").copyWithMti("1442".equals("x") ? "1442" : "1100"), Reason.BAD_PRESENTMENT);
    }

    // ---- the two families stay apart ----

    @Test
    void aDisputeRecordDoesNotBelongInAClearingFileNorAClearingRecordInADisputeFile() {
        assertThatThrownBy(() -> codec.encode(file(chargeback(1, "000000000100", "OTHER", "n")), "B0001.clr"))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.getMessage()).contains("does not belong in a clearing file"));
        assertThatThrownBy(() -> codec.encode(file(representment(1, "000000000100", "n")), "B0001.clr")).isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> codec.encode(file(ClearingFileCodecTest.purchase(1, "000000000100")), DSP))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.getMessage()).contains("does not belong in a dispute file"));
        assertThatThrownBy(() -> codec.encode(file(ClearingFileCodecTest.refund(1, "000000000100")), DSP)).isInstanceOf(ClearingFileException.class);
    }

    @Test
    void theSameCheckHoldsWhenReading() {
        // a dispute file whose bytes are stored under a clearing file name was sealed with that name, so it needs the kind check on read:
        byte[] sealed = codec.seal(codec.plainText(file(chargeback(1, "000000000100", "OTHER", "n"))), "B0001.clr");
        assertThatThrownBy(() -> codec.decode(sealed, "B0001.clr")).isInstanceOfSatisfying(ClearingFileException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.BAD_PRESENTMENT));
        byte[] clearing = codec.seal(codec.plainText(file(ClearingFileCodecTest.purchase(1, "000000000100"))), DSP);
        assertThatThrownBy(() -> codec.decode(clearing, DSP)).isInstanceOf(ClearingFileException.class);
    }

    @Test
    void otherFileNamesAreNotKindChecked() {
        ClearingFile mixed = file(chargeback(1, "000000000100", "OTHER", "n"), ClearingFileCodecTest.purchase(2, "000000000200"));
        assertThat(codec.decode(codec.encode(mixed, "whatever.bin"), "whatever.bin")).isEqualTo(mixed);
    }

    // ---- identity of a record ----

    @Test
    void aChargebackAndARepresentmentOfTheSamePaymentAreDifferentItemsButTwoChargebacksAreTheSame() {
        Message cb = chargeback(1, "000000000100", "OTHER", "n");
        Message rep = representment(1, "000000000100", "n");
        assertThat(ClearingFile.key(cb)).isNotEqualTo(ClearingFile.key(rep));
        assertThatThrownBy(() -> codec.encode(file(cb, chargeback(1, "000000000300", "DUPLICATE", "again")), DSP))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DUPLICATE_ITEM));
        assertThat(codec.decode(codec.encode(file(cb, rep), DSP), DSP).presentments()).hasSize(2);
    }

    @Test
    void theKindOfARecord() {
        assertThat(ClearingFile.kindOf(chargeback(1, "000000000100", "OTHER", "n"))).isEqualTo(RecordKind.CHARGEBACK);
        assertThat(ClearingFile.kindOf(representment(1, "000000000100", "n"))).isEqualTo(RecordKind.REPRESENTMENT);
        assertThat(ClearingFile.kindOf(ClearingFileCodecTest.purchase(1, "000000000100"))).isEqualTo(RecordKind.PURCHASE);
        assertThat(ClearingFile.kindOf(ClearingFileCodecTest.refund(1, "000000000100"))).isEqualTo(RecordKind.REFUND);
        assertThat(RecordKind.CHARGEBACK.isDispute()).isTrue();
        assertThat(RecordKind.REPRESENTMENT.isDispute()).isTrue();
        assertThat(RecordKind.PURCHASE.isDispute()).isFalse();
        assertThat(RecordKind.REFUND.isDispute()).isFalse();
    }

    // ---- damage, acknowledgements, the dump ----

    @Test
    void everyFlippedBitInADisputeFileIsRefused() {
        byte[] bytes = codec.encode(file(chargeback(1, "000000000100", "OTHER", "n"), representment(2, "000000000200", "evidence")), DSP);
        for (int i = 0; i < bytes.length; i++) {
            byte[] bad = bytes.clone();
            bad[i] ^= 0x04;
            assertThatThrownBy(() -> codec.decode(bad, DSP)).isInstanceOf(ClearingFileException.class);
        }
    }

    @Test
    void theDisputeResultsAreValidAcknowledgementResultsThatCountAsDone() {
        ClearingAck ack = ClearingAck.accepted("D0000001", List.of(new ClearingAck.Item(1, "CHARGEBACK_ACCEPTED", ""),
                new ClearingAck.Item(2, "REPRESENTMENT_ACCEPTED", ""), new ClearingAck.Item(3, "REFUSED", "no such payment")));
        ClearingAck back = codec.decodeAck(codec.encodeAck(ack, "D0000001.ack"), "D0000001.ack");
        assertThat(back).isEqualTo(ack);
        assertThat(back.items()).extracting(ClearingAck.Item::cleared).containsExactly(true, true, false);
    }

    @Test
    void theDumpNamesTheKindOfEachRecordAndMasksTheCard() {
        String text = ClearingDump.describe(file(chargeback(1, "000000000100", "OTHER", "n"), representment(2, "000000000200", "evidence")));
        assertThat(text).contains("#1  chargeback").contains("#2  representment").contains("CB|OTHER|n").contains("REP|evidence")
                .contains("999900******0012").doesNotContain(PAN);
    }

    @Test
    void noCardNumberOrReadableTextInTheWrittenFile() {
        byte[] bytes = codec.encode(file(chargeback(1, "000000000100", "OTHER", "my note")), DSP);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain(PAN).doesNotContain("my note").doesNotContain("1442").doesNotContain("FILEHDR");
    }

    @Test
    void randomDisputeFilesRoundTripAndRandomPlainTextNeverCrashesTheReader() {
        Random random = new Random(5);
        String reasons = String.join(",", ClearingFile.DISPUTE_REASONS);
        String[] all = reasons.split(",");
        for (int round = 0; round < 2_000; round++) {
            java.util.ArrayList<Message> items = new java.util.ArrayList<>();
            for (int i = 0; i < random.nextInt(5); i++) {
                String amount = String.format("%012d", 1 + (long) (random.nextDouble() * 99_999_999_999L));
                items.add(random.nextBoolean() ? chargeback(i + 1, amount, all[random.nextInt(all.length)], "n" + random.nextInt(1000))
                        : representment(i + 1, amount, "e" + random.nextInt(1000)));
            }
            ClearingFile f = file(items.toArray(new Message[0]));
            assertThat(codec.decode(codec.encode(f, DSP), DSP)).isEqualTo(f);
        }
        byte[] plain = codec.plainText(file(chargeback(1, "000000000100", "OTHER", "n")));
        for (int round = 0; round < 2_000; round++) {
            byte[] mutated = plain.clone();
            for (int c = 0; c < 1 + random.nextInt(3); c++) {
                mutated[random.nextInt(mutated.length)] = (byte) (random.nextInt(95) + 32);
            }
            try {
                codec.parse(mutated);
            } catch (ClearingFileException expected) {
                // refused with a reason is fine
            }
        }
    }
}
