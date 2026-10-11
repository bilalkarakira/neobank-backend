package com.bilal.cardnet.iso8583.clearing;

import com.bilal.cardnet.iso8583.clearing.ClearingAck.Item;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClearingAckTest {

    static final String NAME = "ACQ-1.ack";
    final ClearingFileCodec codec = ClearingFileCodec.withSecret("test-secret");

    private Reason reasonOfPlain(String plain) {
        try {
            ClearingAck.parse(plain.getBytes(StandardCharsets.US_ASCII));
            return null;
        } catch (ClearingFileException e) {
            return e.reason();
        }
    }

    @Test
    void roundTripAcceptedAndRejected() {
        ClearingAck accepted = ClearingAck.accepted("B1", List.of(new Item(1, "POSTED", ""), new Item(2, "REFUSED", "no route for this card"),
                new Item(3, "FORCE_POSTED", "unknown authorization")));
        assertThat(codec.decodeAck(codec.encodeAck(accepted, NAME), NAME)).isEqualTo(accepted);
        ClearingAck rejected = ClearingAck.rejected("B2", "HASH_MISMATCH: the presentments do not match the trailer hash");
        assertThat(codec.decodeAck(codec.encodeAck(rejected, NAME), NAME)).isEqualTo(rejected);
        assertThat(codec.decodeAck(codec.encodeAck(ClearingAck.accepted("B3", List.of()), NAME), NAME).items()).isEmpty();
    }

    @Test
    void clearedMeansPostedForcedRefundedOrAlreadyCaptured() {
        for (String r : List.of("POSTED", "FORCE_POSTED", "REFUND_POSTED", "DUPLICATE")) {
            assertThat(new Item(1, r, "").cleared()).as(r).isTrue();
        }
        for (String r : List.of("REJECTED", "REFUSED", "POSTED_X", "X")) {
            assertThat(new Item(1, r, "").cleared()).as(r).isFalse();
        }
    }

    @Test
    void reasonsAreCleanedNotTrusted() {
        Item item = new Item(1, "REFUSED", "a|b\ncé " + "x".repeat(300));
        assertThat(item.reason()).doesNotContain("|").doesNotContain("\n").hasSizeLessThanOrEqualTo(120);
        ClearingAck back = codec.decodeAck(codec.encodeAck(ClearingAck.accepted("B1", List.of(item)), NAME), NAME);
        assertThat(back.items().get(0)).isEqualTo(item);
    }

    @Test
    void lookupByRecordNumber() {
        ClearingAck ack = ClearingAck.accepted("B1", List.of(new Item(1, "POSTED", ""), new Item(3, "REFUSED", "x")));
        assertThat(ack.item(3).result()).isEqualTo("REFUSED");
        assertThat(ack.item(2)).isNull();
    }

    @Test
    void invalidConstructionIsRefused() {
        assertThatThrownBy(() -> new Item(0, "POSTED", "")).isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> new Item(1, "posted", "")).isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> new Item(1, null, "")).isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> new ClearingAck(" ", ClearingAck.Status.ACCEPTED, "", List.of())).isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> new ClearingAck("B", ClearingAck.Status.REJECTED, "r", List.of(new Item(1, "POSTED", ""))))
                .isInstanceOf(ClearingFileException.class);
    }

    @Test
    void everyFlippedBitAndTruncationIsRefused() {
        byte[] bytes = codec.encodeAck(ClearingAck.accepted("B1", List.of(new Item(1, "POSTED", ""))), NAME);
        for (int i = 0; i < bytes.length; i++) {
            byte[] bad = bytes.clone();
            bad[i] ^= 0x10;
            assertThatThrownBy(() -> codec.decodeAck(bad, NAME)).isInstanceOf(ClearingFileException.class);
        }
        for (int n = 0; n < bytes.length; n++) {
            byte[] cut = java.util.Arrays.copyOf(bytes, n);
            assertThatThrownBy(() -> codec.decodeAck(cut, NAME)).isInstanceOf(ClearingFileException.class);
        }
    }

    @Test
    void wrongKeyNameOrKindIsRefused() {
        ClearingAck ack = ClearingAck.accepted("B1", List.of());
        byte[] bytes = codec.encodeAck(ack, NAME);
        assertThatThrownBy(() -> ClearingFileCodec.withSecret("x").decodeAck(bytes, NAME))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DECRYPT_FAILED));
        assertThatThrownBy(() -> codec.decodeAck(bytes, "other.ack"))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DECRYPT_FAILED));
        // an acknowledgement is not a clearing file and a clearing file is not an acknowledgement
        assertThatThrownBy(() -> codec.decode(bytes, NAME))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_A_CLEARING_FILE));
        byte[] clearing = codec.encode(ClearingFileCodecTest.file(), "F.clr");
        assertThatThrownBy(() -> codec.decodeAck(clearing, "F.clr"))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_A_CLEARING_FILE));
    }

    @Test
    void malformedPlainText() {
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nACKEND|0\n")).isNull();
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nACKEND|0")).isEqualTo(Reason.BAD_TRAILER);
        assertThat(reasonOfPlain("")).isEqualTo(Reason.BAD_TRAILER);
        assertThat(reasonOfPlain("\n")).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOfPlain("ACKEND|0\n")).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOfPlain("HDR|B|ACCEPTED|\nACKEND|0\n")).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOfPlain("ACKHDR|B|MAYBE|\nACKEND|0\n")).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED\nACKEND|0\n")).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nEND|0\n")).isEqualTo(Reason.BAD_TRAILER);
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nACKEND|1\n")).isEqualTo(Reason.COUNT_MISMATCH);
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nITEM|1|POSTED|\nACKEND|0\n")).isEqualTo(Reason.COUNT_MISMATCH);
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nITEM|1|POSTED|\nACKEND|1\n")).isNull();
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nITEM|x|POSTED|\nACKEND|1\n")).isEqualTo(Reason.BAD_RECORD);
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nITEM|01|POSTED|\nACKEND|1\n")).isEqualTo(Reason.BAD_RECORD);
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nITEM|1|POSTED\nACKEND|1\n")).isEqualTo(Reason.BAD_RECORD);
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nROW|1|POSTED|\nACKEND|1\n")).isEqualTo(Reason.BAD_RECORD);
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\nITEM|1|posted|\nACKEND|1\n")).isEqualTo(Reason.BAD_RECORD);
        assertThat(reasonOfPlain("ACKHDR|B|REJECTED|why\nITEM|1|POSTED|\nACKEND|1\n")).isEqualTo(Reason.BAD_RECORD);
        assertThat(reasonOfPlain("ACKHDR|B|ACCEPTED|\n\u0001\nACKEND|0\n")).isEqualTo(Reason.BAD_RECORD);
    }

    @Test
    void randomPlainTextNeverCrashesTheParser() {
        Random random = new Random(3);
        byte[] valid = "ACKHDR|B1|ACCEPTED|\nITEM|1|POSTED|\nITEM|2|REFUSED|why\nACKEND|2\n".getBytes(StandardCharsets.US_ASCII);
        for (int round = 0; round < 5_000; round++) {
            byte[] mutated = valid.clone();
            for (int c = 0; c < 1 + random.nextInt(4); c++) {
                mutated[random.nextInt(mutated.length)] = (byte) (random.nextInt(100) + 10);
            }
            try {
                ClearingAck.parse(mutated);
            } catch (ClearingFileException expected) {
                // refused with a reason is fine; any other exception type fails the test
            }
        }
    }

    @Test
    void writeAndReadAtomically(@TempDir Path dir) throws Exception {
        ClearingAck ack = ClearingAck.accepted("B1", List.of(new Item(1, "POSTED", "")));
        Path written = codec.writeAck(dir, NAME, ack);
        assertThat(codec.readAck(written)).isEqualTo(ack);
        try (var list = java.nio.file.Files.list(dir)) {
            assertThat(list.map(p -> p.getFileName().toString())).containsExactly(NAME);
        }
    }
}
