package com.bilal.cardnet.iso8583.clearing;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClearingFileCodecTest {

    static final String PAN = "9999000000000012";
    static final String NAME = "ACQ-20260101-0001.clr";

    final ClearingFileCodec codec = ClearingFileCodec.withSecret("test-secret");

    static Message purchase(int n, String amount) {
        return Message.of("1240").set(2, PAN).set(3, "000000").set(4, amount).set(7, "0101120000")
                .set(11, String.format("%06d", n)).set(12, "120000").set(13, "0101").set(14, "2912").set(18, "5411")
                .set(32, "00000000042").set(37, String.format("%012d", n)).set(38, "A1B2C3").set(41, "TERM0001")
                .set(42, "MERCHANT000001 ").set(43, "SHOP".repeat(10)).set(49, "840");
    }

    static Message refund(int n, String amount) {
        return purchase(n, amount).set(3, "200000").remove(38);
    }

    static ClearingFile file(Message... items) {
        return new ClearingFile("B0001", LocalDate.of(2026, 1, 1), "ACQ", "NET", "USD", List.of(items));
    }

    @Test
    void roundTripKeepsEverything() {
        ClearingFile in = file(purchase(1, "000000005000"), purchase(2, "000000000150"), refund(3, "000000000099"));
        ClearingFile out = codec.decode(codec.encode(in, NAME), NAME);
        assertThat(out).isEqualTo(in);
        assertThat(out.controlTotal()).isEqualTo(5249);
    }

    @Test
    void emptyFileIsLegal() {
        ClearingFile out = codec.decode(codec.encode(file(), NAME), NAME);
        assertThat(out.presentments()).isEmpty();
        assertThat(out.controlTotal()).isZero();
    }

    @Test
    void noCardNumberOrReadableTextInTheFile() {
        byte[] bytes = codec.encode(file(purchase(1, "000000005000")), NAME);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain(PAN).doesNotContain("9999").doesNotContain("FILEHDR").doesNotContain("1240");
    }

    @Test
    void everySingleFlippedBitIsRefused() {
        byte[] bytes = codec.encode(file(purchase(1, "000000005000"), refund(2, "000000000100")), NAME);
        for (int i = 0; i < bytes.length; i++) {
            for (int bit = 0; bit < 8; bit += 3) {
                byte[] bad = bytes.clone();
                bad[i] ^= (byte) (1 << bit);
                final int at = i;
                assertThatThrownBy(() -> codec.decode(bad, NAME)).as("byte %d", at).isInstanceOf(ClearingFileException.class);
            }
        }
    }

    @Test
    void everyTruncationIsRefused() {
        byte[] bytes = codec.encode(file(purchase(1, "000000005000")), NAME);
        for (int length = 0; length < bytes.length; length++) {
            byte[] cut = java.util.Arrays.copyOf(bytes, length);
            assertThatThrownBy(() -> codec.decode(cut, NAME)).isInstanceOf(ClearingFileException.class);
        }
    }

    @Test
    void appendedBytesAreRefused() {
        byte[] bytes = codec.encode(file(purchase(1, "000000005000")), NAME);
        byte[] longer = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        assertThatThrownBy(() -> codec.decode(longer, NAME)).isInstanceOfSatisfying(ClearingFileException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.DECRYPT_FAILED));
    }

    @Test
    void wrongKeyOrWrongNameIsDecryptFailed() {
        byte[] bytes = codec.encode(file(purchase(1, "000000005000")), NAME);
        assertThatThrownBy(() -> ClearingFileCodec.withSecret("other").decode(bytes, NAME))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DECRYPT_FAILED));
        assertThatThrownBy(() -> codec.decode(bytes, "ACQ-20260101-0002.clr"))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DECRYPT_FAILED));
    }

    @Test
    void notAClearingFile() {
        assertThatThrownBy(() -> codec.decode("hello".getBytes(StandardCharsets.US_ASCII), NAME))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_A_CLEARING_FILE));
        assertThatThrownBy(() -> codec.decode(null, NAME)).isInstanceOf(ClearingFileException.class);
        byte[] bytes = codec.encode(file(), NAME);
        bytes[0] = 'X';
        assertThatThrownBy(() -> codec.decode(bytes, NAME)).isInstanceOfSatisfying(ClearingFileException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_A_CLEARING_FILE));
    }

    // ---- files that decrypt fine but are wrong inside (a buggy or malicious sender with the key) -----------------

    private Reason reasonOf(String... lines) {
        return reasonOfPlain(String.join("\n", lines) + "\n");
    }

    private Reason reasonOfPlain(String plain) {
        byte[] sealed = codec.seal(plain.getBytes(StandardCharsets.US_ASCII), NAME);
        try {
            codec.decode(sealed, NAME);
            return null;
        } catch (ClearingFileException e) {
            return e.reason();
        }
    }

    private static String pack(Message m) {
        return new String(new com.bilal.cardnet.iso8583.Packager().pack(m), StandardCharsets.US_ASCII);
    }

    private static String sha(String... lines) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String l : lines) {
                d.update((l + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String header() {
        return pack(Message.of("1644").set(48, "FILEHDR|B0001|20260101|ACQ|NET|USD"));
    }

    private static String trailer(int count, long total, String hash) {
        return pack(Message.of("1644").set(48, "FILETRL|" + count + "|" + total + "|" + hash));
    }

    @Test
    void handBuiltValidFileIsAccepted() {
        String a = pack(purchase(1, "000000000100"));
        assertThat(reasonOf(header(), a, trailer(1, 100, sha(a)))).isNull();
    }

    @Test
    void countTotalAndHashMismatchesHaveTheirOwnReasons() {
        String a = pack(purchase(1, "000000000100"));
        assertThat(reasonOf(header(), a, trailer(2, 100, sha(a)))).isEqualTo(Reason.COUNT_MISMATCH);
        assertThat(reasonOf(header(), a, trailer(1, 101, sha(a)))).isEqualTo(Reason.TOTAL_MISMATCH);
        assertThat(reasonOf(header(), a, trailer(1, 100, sha("x")))).isEqualTo(Reason.HASH_MISMATCH);
    }

    @Test
    void deletedReorderedOrSwappedRecordsBreakTheHash() {
        String a = pack(purchase(1, "000000000100"));
        String b = pack(purchase(2, "000000000200"));
        String t = trailer(2, 300, sha(a, b));
        assertThat(reasonOf(header(), a, b, t)).isNull();
        assertThat(reasonOf(header(), b, a, t)).isEqualTo(Reason.HASH_MISMATCH);
        assertThat(reasonOf(header(), a, a, t)).isEqualTo(Reason.HASH_MISMATCH);
        assertThat(reasonOf(header(), a, trailer(2, 300, sha(a, b)))).isEqualTo(Reason.COUNT_MISMATCH);
    }

    @Test
    void headerAndTrailerProblems() {
        String a = pack(purchase(1, "000000000100"));
        String t = trailer(1, 100, sha(a));
        assertThat(reasonOf(a, t)).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOf(header(), a)).isEqualTo(Reason.BAD_TRAILER);
        assertThat(reasonOf(header())).isEqualTo(Reason.BAD_TRAILER);
        assertThat(reasonOfPlain("")).isEqualTo(Reason.BAD_TRAILER);
        assertThat(reasonOfPlain(header() + "\n" + a + "\n" + t)).isEqualTo(Reason.BAD_TRAILER); // no final LF
        assertThat(reasonOf(pack(Message.of("1644").set(48, "FILEHDR|B0001|20260101|ACQ|NET")), a, t)).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOf(pack(Message.of("1644").set(48, "FILEHDR|B0001|20261301|ACQ|NET|USD")), a, t)).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOf(pack(Message.of("1644").set(48, "FILEHDR|B0001|20260101|ACQ|NET|usd")), a, t)).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOf(pack(Message.of("1644").set(48, "FILEHDR|B 1|20260101|ACQ|NET|USD")), a, t)).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOf(pack(Message.of("1240").set(48, "FILEHDR|B0001|20260101|ACQ|NET|USD")), a, t)).isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOf(pack(Message.of("1644").set(48, "FILEHDR|B0001|20260101|ACQ|NET|USD").set(41, "TERM0001")), a, t))
                .isEqualTo(Reason.BAD_HEADER);
        assertThat(reasonOf(header(), a, pack(Message.of("1644").set(48, "FILETRL|1|100")))).isEqualTo(Reason.BAD_TRAILER);
    }

    @Test
    void badRecordsAndPresentments() {
        String good = pack(purchase(1, "000000000100"));
        String garbage = "not a message at all";
        assertThat(reasonOf(header(), garbage, trailer(1, 0, sha(garbage)))).isEqualTo(Reason.BAD_RECORD);

        String aMessage1100 = pack(purchase(1, "000000000100").copyWithMti("0100"));
        assertThat(reasonOf(header(), aMessage1100, trailer(1, 100, sha(aMessage1100)))).isEqualTo(Reason.BAD_PRESENTMENT);

        String midHeader = header();
        assertThat(reasonOf(header(), good, midHeader, trailer(2, 100, sha(good, midHeader)))).isEqualTo(Reason.BAD_PRESENTMENT);

        for (int missing : new int[] {2, 3, 4, 7, 11, 12, 13, 32, 37, 41, 42, 49, 38}) {
            String line = pack(purchase(1, "000000000100").remove(missing));
            assertThat(reasonOf(header(), line, trailer(1, 100, sha(line)))).as("DE %d", missing).isEqualTo(Reason.MISSING_ELEMENT);
        }

        String code = pack(purchase(1, "000000000100").set(3, "010000"));
        assertThat(reasonOf(header(), code, trailer(1, 100, sha(code)))).isEqualTo(Reason.BAD_PRESENTMENT);
        String zero = pack(purchase(1, "000000000000"));
        assertThat(reasonOf(header(), zero, trailer(1, 0, sha(zero)))).isEqualTo(Reason.BAD_PRESENTMENT);
    }

    @Test
    void sameItemTwiceIsADuplicateButSameStanOnDifferentDayOrCodeIsNot() {
        String a = pack(purchase(1, "000000000100"));
        assertThat(reasonOf(header(), a, a, trailer(2, 200, sha(a, a)))).isEqualTo(Reason.DUPLICATE_ITEM);
        String other = pack(purchase(1, "000000000100").set(13, "0102"));
        assertThat(reasonOf(header(), a, other, trailer(2, 200, sha(a, other)))).isNull();
        String ref = pack(refund(1, "000000000100"));
        assertThat(reasonOf(header(), a, ref, trailer(2, 200, sha(a, ref)))).isNull();
        // the key ignores the amount: the same authorization presented for two amounts is still the same item
        String different = pack(purchase(1, "000000000300"));
        assertThat(reasonOf(header(), a, different, trailer(2, 400, sha(a, different)))).isEqualTo(Reason.DUPLICATE_ITEM);
    }

    @Test
    void bytesAfterTheLastCompleteRecordAreRefused() {
        String a = pack(purchase(1, "000000000100"));
        String good = header() + "\n" + a + "\n" + trailer(1, 100, sha(a)) + "\n";
        assertThat(reasonOfPlain(good)).isNull();
        assertThat(reasonOfPlain(good + "junk")).isEqualTo(Reason.BAD_TRAILER);
    }

    @Test
    void sameStanWithAnotherRrnIsAnotherItem() {
        String a = pack(purchase(1, "000000000100"));
        String other = pack(purchase(1, "000000000100").set(37, "999999999999"));
        assertThat(reasonOf(header(), a, other, trailer(2, 200, sha(a, other)))).isNull();
    }

    @Test
    void controlTotalAcrossManyRecordsAndLargeAmounts() {
        String a = pack(purchase(1, "999999999999"));
        String b = pack(purchase(2, "999999999999"));
        assertThat(reasonOf(header(), a, b, trailer(2, 1_999_999_999_998L, sha(a, b)))).isNull();
    }

    @Test
    void writerRefusesBadInputBeforeWriting() {
        assertThatThrownBy(() -> codec.encode(file(purchase(1, "000000000100"), purchase(1, "000000000100")), NAME))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DUPLICATE_ITEM));
        assertThatThrownBy(() -> codec.encode(file(refund(1, "000000000100").set(4, "000000000000")), NAME))
                .isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> codec.encode(file(purchase(1, "000000000100").remove(38)), NAME))
                .isInstanceOfSatisfying(ClearingFileException.class, e -> assertThat(e.reason()).isEqualTo(Reason.MISSING_ELEMENT));
        assertThatThrownBy(() -> codec.encode(file(Message.of("0100").set(2, PAN)), NAME)).isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> new ClearingFile("bad id", LocalDate.now(), "A", "B", "USD", List.of()))
                .isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> new ClearingFile("ok", LocalDate.now(), "A", "B", "usd", List.of()))
                .isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> new ClearingFile("ok", null, "A", "B", "USD", List.of())).isInstanceOf(ClearingFileException.class);
    }

    @Test
    void keyMustBe32Bytes() {
        assertThatThrownBy(() -> new ClearingFileCodec(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClearingFileCodec(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameContentEncryptsDifferentlyEachTime() {
        ClearingFile f = file(purchase(1, "000000000100"));
        assertThat(codec.encode(f, NAME)).isNotEqualTo(codec.encode(f, NAME));
    }

    @Test
    void writeIsAtomicReadableAndNameBound(@TempDir Path dir) throws Exception {
        ClearingFile f = file(purchase(1, "000000005000"));
        Path written = codec.write(dir.resolve("out"), NAME, f);
        assertThat(written.getFileName().toString()).isEqualTo(NAME);
        try (var list = Files.list(dir.resolve("out"))) {
            assertThat(list.map(p -> p.getFileName().toString())).containsExactly(NAME);
        }
        assertThat(codec.read(written)).isEqualTo(f);
        Path renamed = Files.move(written, dir.resolve("out").resolve("renamed.clr"));
        assertThatThrownBy(() -> codec.read(renamed)).isInstanceOfSatisfying(ClearingFileException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.DECRYPT_FAILED));
    }

    @Test
    void dumpMasksTheCardNumber() {
        String text = ClearingDump.describe(file(purchase(1, "000000005000")));
        assertThat(text).contains("1 presentment(s), control total 5000").contains("999900******0012").doesNotContain(PAN);
    }

    @Test
    void randomFilesRoundTrip() {
        Random random = new Random(42);
        for (int round = 0; round < 10_000; round++) {
            int count = random.nextInt(6);
            List<Message> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String amount = String.format("%012d", 1 + (long) (random.nextDouble() * 99_999_999_999L));
                Message m = random.nextBoolean() ? purchase(i + 1, amount) : refund(i + 1, amount);
                m.set(43, randomPrintable(random, 40));
                items.add(m);
            }
            ClearingFile f = file(items.toArray(new Message[0]));
            assertThat(codec.decode(codec.encode(f, NAME), NAME)).isEqualTo(f);
        }
    }

    @Test
    void randomBytesNeverCrashTheReader() {
        Random random = new Random(7);
        for (int round = 0; round < 2_000; round++) {
            byte[] junk = new byte[random.nextInt(200)];
            random.nextBytes(junk);
            if (junk.length > 5 && random.nextBoolean()) {
                System.arraycopy("NCLR1".getBytes(StandardCharsets.US_ASCII), 0, junk, 0, 5);
            }
            assertThatThrownBy(() -> codec.decode(junk, NAME)).isInstanceOf(ClearingFileException.class);
        }
    }

    @Test
    void randomPlainTextNeverCrashesTheParser() {
        Random random = new Random(11);
        String a = pack(purchase(1, "000000000100"));
        byte[] valid = (header() + "\n" + a + "\n" + trailer(1, 100, sha(a)) + "\n").getBytes(StandardCharsets.US_ASCII);
        for (int round = 0; round < 3_000; round++) {
            byte[] mutated = valid.clone();
            int changes = 1 + random.nextInt(3);
            for (int c = 0; c < changes; c++) {
                mutated[random.nextInt(mutated.length)] = (byte) (random.nextInt(95) + 32);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(mutated);
            try {
                codec.parse(out.toByteArray());
            } catch (ClearingFileException expected) {
                // refused with a reason: fine; anything else (NumberFormatException, ...) fails the test
            }
        }
    }

    private static String randomPrintable(Random random, int length) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < length; i++) {
            s.append((char) (0x20 + random.nextInt(0x5F)));
        }
        return s.toString();
    }
}
