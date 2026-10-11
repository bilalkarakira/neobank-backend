package com.bilal.cardnet.iso8583;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Messages written out by hand from docs/ISO8583-spec.md (bitmaps worked out on paper), so the codec is checked against
 * the specification and not only against itself.
 */
class PackagerVectorTest {

    private final Packager packager = new Packager();

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    // 0200, DE 2 3 4 11 41: bits 2,3,4 -> 0x70 | bit 11 -> byte 1 0x20 | bit 41 -> byte 5 0x80
    private static final String PURCHASE = "0200" + "7020000000800000"
            + "16" + "9999001234567891" // DE 2, LLVAR
            + "000000" // DE 3
            + "000000002500" // DE 4: 25.00
            + "000123" // DE 11
            + "TERM0001"; // DE 41

    // 0800, DE 7 11 70: primary bits 1 (secondary follows), 7, 11 -> 0x82 0x20; secondary bit 70 -> byte 0 mask 0x04
    private static final String SIGN_ON = "0800" + "8220000000000000" + "0400000000000000"
            + "1005123045" + "000001" + "301";

    @Test
    void packsAPurchaseExactlyAsWrittenByHand() {
        Message message = Message.of("0200").set(2, "9999001234567891").set(3, "000000").set(4, "000000002500")
                .set(11, "000123").set(41, "TERM0001");

        assertThat(text(packager.pack(message))).isEqualTo(PURCHASE);
    }

    @Test
    void unpacksTheHandWrittenPurchase() {
        Message message = packager.unpack(PURCHASE.getBytes(StandardCharsets.US_ASCII));

        assertThat(message.mti()).isEqualTo("0200");
        assertThat(message.fields().keySet()).containsExactly(2, 3, 4, 11, 41);
        assertThat(message.get(2)).isEqualTo("9999001234567891");
        assertThat(message.get(4)).isEqualTo("000000002500");
        assertThat(message.get(41)).isEqualTo("TERM0001");
    }

    @Test
    void aFieldAbove64AddsTheSecondaryBitmapAndSetsBitOne() {
        Message message = Message.of("0800").set(7, "1005123045").set(11, "000001").set(70, "301");

        assertThat(text(packager.pack(message))).isEqualTo(SIGN_ON);
        assertThat(packager.unpack(SIGN_ON.getBytes(StandardCharsets.US_ASCII))).isEqualTo(message);
    }

    @Test
    void thePrimaryBitmapAloneIsUsedWhenNothingAbove64IsPresent() {
        String packed = text(packager.pack(Message.of("0100").set(3, "000000")));

        assertThat(packed).isEqualTo("0100" + "2000000000000000" + "000000"); // bit 3 only, no bit 1, no secondary
    }

    @Test
    void aThreeDigitLengthPrefixIsUsedForLlvarThree() {
        // DE 48 = bit 48: byte 5, mask 0x01
        String expected = "0100" + "0000000000010000" + "003" + "ABC";

        assertThat(text(packager.pack(Message.of("0100").set(48, "ABC")))).isEqualTo(expected);
        assertThat(packager.unpack(expected.getBytes(StandardCharsets.US_ASCII)).get(48)).isEqualTo("ABC");
    }

    @Test
    void binaryDataTravelsAsHexAndItsLengthCountsHexCharacters() {
        // DE 55 = bit 55: byte 6, mask 0x02 ; 4 bytes = 8 hex characters = "008"
        String expected = "0100" + "0000000000000200" + "008" + "9F260811";

        assertThat(text(packager.pack(Message.of("0100").set(55, "9F260811")))).isEqualTo(expected);
        assertThat(packager.unpack(expected.getBytes(StandardCharsets.US_ASCII)).get(55)).isEqualTo("9F260811");
    }

    @Test
    void lowerCaseHexInABitmapIsAcceptedOnInputButNeverProduced() {
        // DE 3 and 4 -> byte 0 = 0x30; DE 11, 12, 13, 14 -> byte 1 = 0x20 + 0x10 + 0x08 + 0x04 = 0x3C (a letter in the bitmap)
        String upper = "0200" + "303C000000000000" + "000000" + "000000002500" + "000123" + "101530" + "0507" + "2812";
        String lowerCase = upper.replace("303C", "303c");

        Message fromLower = packager.unpack(lowerCase.getBytes(StandardCharsets.US_ASCII));

        assertThat(fromLower.fields().keySet()).containsExactly(3, 4, 11, 12, 13, 14);
        assertThat(text(packager.pack(fromLower))).isEqualTo(upper);
    }

    @Test
    void anEmptyMessageHasAnAllZeroBitmap() {
        assertThat(text(packager.pack(Message.of("0810")))).isEqualTo("0810" + "0000000000000000");
        assertThat(packager.unpack("08100000000000000000".getBytes(StandardCharsets.US_ASCII)).fields()).isEmpty();
    }
}
