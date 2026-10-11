package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.iso8583.PinBlock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RedactTest {

    private static final String PAN = "9999001357924680";
    private static final String PIN_BLOCK = PinBlock.encode("4321", PAN);

    private static Message payment() {
        return Message.of("0100").set(2, PAN).set(3, "000000").set(4, "000000004250").set(7, "1005120000").set(11, "000501")
                .set(12, "120000").set(13, "1005").set(14, "2910").set(18, "5814").set(32, "123456").set(37, "000000000501")
                .set(41, "TERM0001").set(42, "MERCHANT0000001").set(49, "840").set(52, PIN_BLOCK);
    }

    @Test
    void theMiddleOfTheNumberAndTheWholePinBlockAreOverwrittenInTheBytes() {
        byte[] real = new Packager().pack(payment());
        byte[] shown = Redact.redactedBytes(payment());
        String text = new String(shown, StandardCharsets.US_ASCII);

        assertThat(shown).as("same length as the real message").hasSameSizeAs(real);
        assertThat(text).doesNotContain(PAN).doesNotContain("135792").doesNotContain(PIN_BLOCK).doesNotContain(PIN_BLOCK.substring(0, 8));
        assertThat(text).as("the first six and last four survive").contains("999900******4680");
        assertThat(text).contains("****************").as("the PIN block is all stars");
        assertThat(text).as("nothing else is touched").contains("TERM0001").contains("MERCHANT0000001").contains("000000004250");
    }

    @Test
    void theSecurityCodeIsOverwrittenByItsPlaceNotByItsValue() {
        // the code 123 is also the end of the trace number 000123 and part of the amount: only the element itself may be hidden
        Message online = payment().remove(52).set(4, "000000012300").set(11, "000123").set(22, "812").set(25, "59").set(53, "123");
        byte[] real = new Packager().pack(online);
        byte[] shown = Redact.redactedBytes(online);
        String text = new String(shown, StandardCharsets.US_ASCII);

        assertThat(shown).hasSameSizeAs(real);
        assertThat(text).contains("000000012300").contains("000123").contains("812").contains("TERM0001");
        assertThat(text).as("the element is a length and then the code").contains("03***");
        assertThat(text).doesNotContain("03123");
        assertThat(Redact.decoded(online)).contains("Card verification value 2").doesNotContain("  123\n");
    }

    @Test
    void aMessageWithoutAPinIsOnlyMaskedAtTheNumber() {
        String text = new String(Redact.redactedBytes(payment().remove(52)), StandardCharsets.US_ASCII);

        assertThat(text).doesNotContain("135792").contains("999900******4680");
    }

    @Test
    void theDecodedFormMasksTheNumberAndHidesThePin() {
        String decoded = Redact.decoded(payment());

        assertThat(decoded).doesNotContain(PAN).doesNotContain("135792").doesNotContain(PIN_BLOCK).contains("999900******4680");
        assertThat(decoded).contains("DE  52");
    }

    @Test
    void theHexDumpOfARealMessageHasNoReadableNumberOrPinAnywhere() {
        String dump = Redact.hexDump(payment());

        assertThat(dump).doesNotContain(PAN).doesNotContain("135792").doesNotContain(PIN_BLOCK);
        String hexOnly = dump.lines().map(l -> l.replaceAll("[^0-9a-fA-F ]", "")).reduce("", String::concat).replace(" ", "").toLowerCase();
        assertThat(hexOnly).as("the hex of the hidden digits is not in the dump either").doesNotContain("313335373932");
    }

    @Test
    void aMessageThatHasNoNumberIsLeftAlone() {
        Message network = Message.of("0800").set(7, "1005120000").set(11, "000001").set(70, "001");
        assertThat(Redact.hexDump(network)).isNotEmpty();
        assertThat(new String(Redact.redactedBytes(network), StandardCharsets.US_ASCII)).isEqualTo(new String(new Packager().pack(network), StandardCharsets.US_ASCII));
    }
}
