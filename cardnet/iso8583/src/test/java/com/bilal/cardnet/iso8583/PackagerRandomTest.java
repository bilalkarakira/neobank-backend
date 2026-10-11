package com.bilal.cardnet.iso8583;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Generated messages: every legal message survives a round trip, and no input at all can crash or hang the reader. */
class PackagerRandomTest {

    private static final String DIGITS = "0123456789";
    private static final String ALNUM = DIGITS + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final String HEX = "0123456789ABCDEF";

    private final Packager packager = new Packager();

    private static String random(Random random, String alphabet, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    private static String printable() {
        StringBuilder all = new StringBuilder();
        for (char c = 0x20; c <= 0x7E; c++) {
            all.append(c);
        }
        return all.toString();
    }

    static String valueFor(FieldDef def, Random random) {
        String alphabet = switch (def.type()) {
            case N -> DIGITS;
            case AN -> ALNUM;
            case ANS -> printable();
            case B -> HEX;
        };
        int length = def.prefix() == FieldDef.Prefix.FIXED ? def.length() : 1 + random.nextInt(def.length());
        if (def.type() == FieldType.B && length % 2 != 0) {
            length = length == def.length() ? length - 1 : length + 1;
            if (length == 0) {
                length = 2;
            }
        }
        return random(random, alphabet, length);
    }

    static Message randomMessage(Random random) {
        String[] mtis = { "0100", "0110", "0200", "0210", "0400", "0420", "0800", "0810", "1240" };
        Message message = Message.of(mtis[random.nextInt(mtis.length)]);
        for (FieldDef def : Spec.ISO87.all()) {
            if (random.nextInt(3) == 0) {
                message.set(def.number(), valueFor(def, random));
            }
        }
        return message;
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void tenThousandRandomLegalMessagesSurviveARoundTripAndRepackIdentically() {
        Random random = new Random(8583);
        for (int i = 0; i < 10_000; i++) {
            Message message = randomMessage(random);
            byte[] packed = packager.pack(message);
            Message again = packager.unpack(packed);

            assertThat(again).as("message %d", i).isEqualTo(message);
            assertThat(packager.pack(again)).isEqualTo(packed);
        }
    }

    @Test
    void everyDefinedFieldOnItsOwnRoundTripsAtItsShortestAndLongestLength() {
        Random random = new Random(1);
        for (FieldDef def : Spec.ISO87.all()) {
            for (int length : new int[] { def.prefix() == FieldDef.Prefix.FIXED ? def.length() : 1, def.length() }) {
                if (def.type() == FieldType.B && length % 2 != 0) {
                    continue;
                }
                String alphabet = switch (def.type()) {
                    case N -> DIGITS;
                    case AN -> ALNUM;
                    case ANS -> printable();
                    case B -> HEX;
                };
                Message message = Message.of("0200").set(def.number(), random(random, alphabet, length));

                assertThat(packager.unpack(packager.pack(message))).as("DE %d length %d", def.number(), length).isEqualTo(message);
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void damagedMessagesAreEitherReadOrRefusedWithOurExceptionAndNothingElse() {
        Random random = new Random(42);
        for (int i = 0; i < 30_000; i++) {
            byte[] bytes = packager.pack(randomMessage(random));
            switch (random.nextInt(4)) {
                case 0 -> bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256); // one byte changed
                case 1 -> bytes = java.util.Arrays.copyOf(bytes, random.nextInt(bytes.length + 1)); // cut short
                case 2 -> bytes = java.util.Arrays.copyOf(bytes, bytes.length + 1 + random.nextInt(5)); // zero bytes appended
                default -> { // a run of bytes replaced by digits
                    int from = random.nextInt(bytes.length);
                    for (int k = from; k < Math.min(bytes.length, from + 6); k++) {
                        bytes[k] = (byte) ('0' + random.nextInt(10));
                    }
                }
            }
            try {
                packager.unpack(bytes);
            } catch (Iso8583Exception expected) {
                // fine
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void completelyRandomBytesNeverThrowAnythingButOurException() {
        Random random = new Random(7);
        for (int i = 0; i < 30_000; i++) {
            byte[] bytes = new byte[random.nextInt(300)];
            random.nextBytes(bytes);
            try {
                packager.unpack(bytes);
            } catch (Iso8583Exception expected) {
                // fine
            }
        }
        // and random PRINTABLE text, which gets much further into the parser
        for (int i = 0; i < 30_000; i++) {
            String text = random(random, DIGITS + "ABCDEF" + " X", 4 + random.nextInt(120));
            try {
                packager.unpack(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            } catch (Iso8583Exception expected) {
                // fine
            }
        }
    }
}
