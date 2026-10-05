package com.bilal.cardnet.iso8583;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Everything the codec must refuse, on the way out and on the way in. */
class PackagerValidationTest {

    private final Packager packager = new Packager();

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static final String PURCHASE = "0200" + "7020000000800000" + "16" + "9999001234567891" + "000000" + "000000002500"
            + "000123" + "TERM0001";

    // ---- building and packing -------------------------------------------------------------------------------

    @Test
    void anMtiMustBeFourDigits() {
        for (String bad : new String[] { null, "", "020", "02000", "02A0", "0 00", "０２００" }) {
            assertThatThrownBy(() -> Message.of(bad)).isInstanceOf(Iso8583Exception.class);
        }
    }

    @Test
    void fieldOneSixtyFiveAndOutOfRangeNumbersCannotBeSet() {
        Message message = Message.of("0100");
        for (int bad : new int[] { -1, 0, 1, 65, 129, 1000 }) {
            assertThatThrownBy(() -> message.set(bad, "1")).isInstanceOf(Iso8583Exception.class);
        }
    }

    @Test
    void aDataElementTheProfileDoesNotDefineCannotBePacked() {
        Message message = Message.of("0100").set(9, "1");

        assertThatThrownBy(() -> packager.pack(message)).isInstanceOf(Iso8583Exception.class).hasMessageContaining("DE 9")
                .extracting(e -> ((Iso8583Exception) e).field()).isEqualTo(9);
    }

    @Test
    void fixedFieldsNeedTheirExactLengthAndAreNeverPaddedOrTruncated() {
        for (String tooShort : new String[] { "", "00000", "0" }) {
            assertThatThrownBy(() -> packager.pack(Message.of("0100").set(3, tooShort))).isInstanceOf(Iso8583Exception.class);
        }
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(3, "0000000"))).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(41, "TERM1"))).isInstanceOf(Iso8583Exception.class);
    }

    @Test
    void variableFieldsNeedAtLeastOneAndAtMostTheirMaximum() {
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(2, ""))).isInstanceOf(Iso8583Exception.class);
        assertThat(packager.pack(Message.of("0100").set(2, "1".repeat(19)))).isNotEmpty();
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(2, "1".repeat(20)))).isInstanceOf(Iso8583Exception.class);
        assertThat(packager.pack(Message.of("0100").set(48, "A".repeat(999)))).isNotEmpty();
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(48, "A".repeat(1000)))).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(32, "1".repeat(12)))).isInstanceOf(Iso8583Exception.class);
    }

    @Test
    void valuesMustMatchTheirCharacterClass() {
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(3, "00000A"))).isInstanceOf(Iso8583Exception.class); // n
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(37, "RRN 00000001"))).isInstanceOf(Iso8583Exception.class); // an
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(41, "TERMé001"))).isInstanceOf(Iso8583Exception.class); // ans
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(41, "TERM\t001"))).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(52, "00000000000000GG"))).isInstanceOf(Iso8583Exception.class); // b
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(55, "9F2"))).isInstanceOf(Iso8583Exception.class); // odd hex
        assertThatThrownBy(() -> packager.pack(Message.of("0100").set(55, "9f26"))).isInstanceOf(Iso8583Exception.class); // upper case only
    }

    // ---- unpacking ------------------------------------------------------------------------------------------

    @Test
    void everyProperPrefixOfAValidMessageIsRefusedNotHalfRead() {
        byte[] full = ascii(PURCHASE);
        for (int length = 0; length < full.length; length++) {
            byte[] prefix = Arrays.copyOf(full, length);
            assertThatThrownBy(() -> packager.unpack(prefix)).as("prefix of %d bytes", length).isInstanceOf(Iso8583Exception.class);
        }
        assertThat(packager.unpack(full)).isNotNull();
    }

    @Test
    void surplusBytesAfterTheLastFieldAreRefused() {
        assertThatThrownBy(() -> packager.unpack(ascii(PURCHASE + "X"))).isInstanceOf(Iso8583Exception.class)
                .hasMessageContaining("unexpected");
    }

    @Test
    void aBitmapMustBeHexadecimal() {
        assertThatThrownBy(() -> packager.unpack(ascii(PURCHASE.replace("7020000000800000", "70200000008000XY"))))
                .isInstanceOf(Iso8583Exception.class);
    }

    @Test
    void aBitForAnUndefinedElementIsRefusedBecauseItsLengthIsUnknown() {
        // bit 9 -> byte 1 mask 0x40 ; the profile defines no DE 9
        assertThatThrownBy(() -> packager.unpack(ascii("0100" + "0040000000000000" + "123456")))
                .isInstanceOf(Iso8583Exception.class).hasMessageContaining("not define");
    }

    @Test
    void everyUndefinedDataElementNumberIsRefusedWhenItsBitIsSet() {
        for (int number = 2; number <= 128; number++) {
            if (number == 65 || Spec.ISO87.get(number).isPresent()) {
                continue;
            }
            long primary = number <= 64 ? 1L << (64 - number) : 1L << 63;
            long secondary = number > 64 ? 1L << (128 - number) : 0L;
            String wire = "0100" + String.format("%016X", primary) + (number > 64 ? String.format("%016X", secondary) : "") + "1234";
            assertThatThrownBy(() -> packager.unpack(ascii(wire))).as("DE %d", number).isInstanceOf(Iso8583Exception.class);
        }
    }

    @Test
    void aTertiaryBitmapIsNotSupported() {
        // bit 1 (secondary follows) and, in the secondary bitmap, bit 1 = field 65
        assertThatThrownBy(() -> packager.unpack(ascii("0100" + "8000000000000000" + "8000000000000000")))
                .isInstanceOf(Iso8583Exception.class).hasMessageContaining("tertiary");
    }

    @Test
    void aLengthPrefixMustBeDigitsAndWithinTheMaximum() {
        String head = "0100" + "4000000000000000"; // DE 2
        for (String bad : new String[] { "AB", "-1", "0 " }) {
            assertThatThrownBy(() -> packager.unpack(ascii(head + bad + "1".repeat(19)))).as("prefix %s", bad)
                    .isInstanceOf(Iso8583Exception.class).hasMessageContaining("prefix must be digits");
        }
        // a prefix above the maximum (or zero) is refused up front, before any value is read
        for (String outside : new String[] { "00", "20", "99" }) {
            assertThatThrownBy(() -> packager.unpack(ascii(head + outside + "1".repeat(19)))).as("prefix %s", outside)
                    .isInstanceOf(Iso8583Exception.class).hasMessageContaining("outside 1..19");
        }
    }

    @Test
    void aLengthPrefixLargerThanTheRemainingBytesIsRefused() {
        assertThatThrownBy(() -> packager.unpack(ascii("0100" + "4000000000000000" + "16" + "123"))).isInstanceOf(Iso8583Exception.class)
                .hasMessageContaining("ends inside");
    }

    @Test
    void nonPrintableBytesAndNullInputAreRefused() {
        byte[] bytes = ascii(PURCHASE);
        bytes[10] = 0x00;
        assertThatThrownBy(() -> packager.unpack(bytes)).isInstanceOf(Iso8583Exception.class).hasMessageContaining("byte 10 is not printable");
        byte[] high = ascii(PURCHASE);
        high[10] = (byte) 0xC3;
        assertThatThrownBy(() -> packager.unpack(high)).isInstanceOf(Iso8583Exception.class).hasMessageContaining("not printable");
        byte[] tab = ascii(PURCHASE);
        tab[tab.length - 1] = '	'; // inside an ans field: still refused as a control character
        assertThatThrownBy(() -> packager.unpack(tab)).isInstanceOf(Iso8583Exception.class).hasMessageContaining("not printable");
        assertThatThrownBy(() -> packager.unpack(null)).isInstanceOf(Iso8583Exception.class);
    }

    @Test
    void aValueThatBreaksItsCharacterClassIsRefusedOnTheWayIn() {
        assertThatThrownBy(() -> packager.unpack(ascii(PURCHASE.replace("000000002500", "00000000250X"))))
                .isInstanceOf(Iso8583Exception.class).hasMessageContaining("DE 4");
    }

    @Test
    void aMessageBuiltWithTheWrongProfileIsRefusedNotMisread() {
        Packager small = new Packager(new Spec(new FieldDef(3, "Processing code", FieldType.N, FieldDef.Prefix.FIXED, 6)));

        assertThatThrownBy(() -> small.unpack(ascii(PURCHASE))).isInstanceOf(Iso8583Exception.class);
    }
}
