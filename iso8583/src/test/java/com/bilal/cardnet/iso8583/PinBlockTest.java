package com.bilal.cardnet.iso8583;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinBlockTest {

    // Worked out on paper. PIN field for 1234 = 04 12 34 FF FF FF FF FF. PAN 4111111111111111 without its check digit is
    // 411111111111111, whose 12 rightmost digits are 111111111111, so the PAN field is 0000 1111 1111 1111.
    // XOR: 04 12 (34^11=25) (FF^11=EE) EE EE EE EE
    private static final String PAN = "4111111111111111";

    @Test
    void knownVector() {
        assertThat(PinBlock.encode("1234", PAN)).isEqualTo("041225EEEEEEEEEE");
        assertThat(PinBlock.decode("041225EEEEEEEEEE", PAN)).contains("1234");
    }

    @Test
    void lowerCaseHexIsAccepted() {
        assertThat(PinBlock.decode("041225eeeeeeeeee", PAN)).contains("1234");
    }

    @Test
    void theCheckDigitOfThePanDoesNotMatter() {
        // the PAN field uses the digits left of the check digit, so changing only the last digit gives the same block
        assertThat(PinBlock.encode("1234", "4111111111111112")).isEqualTo(PinBlock.encode("1234", PAN));
    }

    @Test
    void aBlockForAnotherPanDecodesToNothing() {
        String block = PinBlock.encode("1234", PAN);
        assertThat(PinBlock.decode(block, "5555555555554444")).isEmpty();
    }

    @Test
    void roundTripsForRandomPinsOfAllLengths() {
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            int length = 4 + random.nextInt(9);
            StringBuilder pin = new StringBuilder();
            for (int d = 0; d < length; d++) {
                pin.append(random.nextInt(10));
            }
            StringBuilder pan = new StringBuilder("999900");
            for (int d = 0; d < 10; d++) {
                pan.append(random.nextInt(10));
            }
            String block = PinBlock.encode(pin.toString(), pan.toString());
            assertThat(block).hasSize(16).matches("[0-9A-F]{16}");
            assertThat(PinBlock.decode(block, pan.toString())).isEqualTo(Optional.of(pin.toString()));
        }
    }

    @Test
    void malformedBlocksAreEmptyNotExceptions() {
        assertThat(PinBlock.decode(null, PAN)).isEmpty();
        assertThat(PinBlock.decode("", PAN)).isEmpty();
        assertThat(PinBlock.decode("041225EEEEEEEEE", PAN)).isEmpty();      // 15 chars
        assertThat(PinBlock.decode("041225EEEEEEEEEEE", PAN)).isEmpty();    // 17 chars
        assertThat(PinBlock.decode("041225EEEEEEEEEG", PAN)).isEmpty();     // not hex
        assertThat(PinBlock.decode("FFFFFFFFFFFFFFFF", PAN)).isEmpty();
        // control nibble not 0, PIN length 3 (too short), non-digit in the PIN, padding that is not F
        assertThat(PinBlock.decode(xorWithPanField("1412 34FF FFFF FFFF", PAN), PAN)).isEmpty();
        assertThat(PinBlock.decode(xorWithPanField("0312 3FFF FFFF FFFF", PAN), PAN)).isEmpty();
        assertThat(PinBlock.decode(xorWithPanField("041A 34FF FFFF FFFF", PAN), PAN)).isEmpty();
        assertThat(PinBlock.decode(xorWithPanField("0412 3400 FFFF FFFF", PAN), PAN)).isEmpty();
        assertThat(PinBlock.decode(xorWithPanField("0D12 3456 7890 123F", PAN), PAN)).as("a 13 digit PIN is too long").isEmpty();
        assertThat(PinBlock.decode(xorWithPanField("0C12 3456 7890 12FF", PAN), PAN)).as("12 digits is the longest").contains("123456789012");
    }

    @Test
    void refusesWhatCannotBeEncoded() {
        assertThatThrownBy(() -> PinBlock.encode("123", PAN)).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> PinBlock.encode("1234567890123", PAN)).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> PinBlock.encode("12a4", PAN)).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> PinBlock.encode(null, PAN)).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> PinBlock.encode("1234", "123")).isInstanceOf(Iso8583Exception.class);
    }

    /** Builds a block from a hand-written PIN field by XOR-ing the PAN field the same way the codec does. */
    private static String xorWithPanField(String pinField, String pan) {
        String field = pinField.replace(" ", "");
        String without = pan.substring(0, pan.length() - 1);
        String panField = "0000" + without.substring(without.length() - 12);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            out.append(Integer.toHexString(Character.digit(field.charAt(i), 16) ^ Character.digit(panField.charAt(i), 16)));
        }
        return out.toString().toUpperCase();
    }
}
