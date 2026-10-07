package com.bilal.cardnet.iso8583;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectorAndHelpersTest {

    private static final String PAN = "9999001234567891";
    private static final String PIN_BLOCK = "041234FFFFFFFFFF";

    private static Message purchase() {
        return Message.of("0200").set(2, PAN).set(3, "000000").set(4, "000000002500").set(11, "000123")
                .set(41, "TERM0001").set(52, PIN_BLOCK);
    }

    // ---- inspector: the same text on every platform ---------------------------------------------------------------

    @Test
    void theReportUsesOnlyLineFeedsWhateverThePlatformIs() {
        // the checked-in samples are compared with this text byte for byte, on Windows, Linux and the build server alike
        String report = Inspector.format(purchase());

        assertThat(report).doesNotContain("\r");
        assertThat(report.lines().count()).isGreaterThan(3);
        assertThat(report).endsWith("\n");
    }

    // ---- inspector: nothing sensitive leaks ---------------------------------------------------------------------

    @Test
    void thePanIsMaskedToSixAndFourDigitsAndThePinBlockIsHidden() {
        String report = Inspector.format(purchase());

        assertThat(report).contains("999900******7891");
        assertThat(report).doesNotContain(PAN).doesNotContain("1234567891").doesNotContain(PIN_BLOCK).doesNotContain("041234");
        assertThat(report).contains("Processing code", "000000", "000000002500", "TERM0001");
    }

    @Test
    void sensitiveValuesAreShownOnlyOnExplicitRequest() {
        String report = Inspector.format(purchase(), Spec.ISO87, true);

        assertThat(report).contains(PAN, PIN_BLOCK);
    }

    @Test
    void toStringNeverContainsAnyValueSoAMessageInALogOrAFailedAssertionLeaksNothing() {
        String text = purchase().toString();

        assertThat(text).doesNotContain(PAN).doesNotContain(PIN_BLOCK).doesNotContain("TERM0001").contains("0200");
    }

    @Test
    void panMaskingKeepsAtMostSixAndFourAndHidesShortNumbersCompletely() {
        assertThat(Inspector.maskPan("9999001234567891")).isEqualTo("999900******7891");
        assertThat(Inspector.maskPan("99990012345678901")).isEqualTo("999900*******8901");
        assertThat(Inspector.maskPan("1234567890")).isEqualTo("**********");
        assertThat(Inspector.maskPan("123")).isEqualTo("***");
    }

    @Test
    void theReportNamesTheMessageTypeInWords() {
        assertThat(Inspector.format(purchase())).contains("MTI 0200", "ISO 8583:1987, Financial, Request, Acquirer");
    }

    @Test
    void chipDataIsDecodedIntoNamedTags() {
        String chip = Tlv.encode("9F26", "1122334455667788") + Tlv.encode("9F02", "000000002500") + Tlv.encode("5F2A", "0840");
        String report = Inspector.format(Message.of("0100").set(55, chip));

        assertThat(report).contains("Application cryptogram", "1122334455667788", "Amount, authorised", "Transaction currency code");
    }

    @Test
    void hexDumpShowsOffsetsBytesAndPrintableText() {
        String dump = Inspector.hexDump("0200 hello".getBytes(StandardCharsets.US_ASCII));

        assertThat(dump).startsWith("0000  30 32 30 30 20 68 65 6C").contains("|0200 hello|");
        assertThat(Inspector.hexDump(new byte[20]).lines().toList()).hasSize(2);
        assertThat(Inspector.hexDump(new byte[0])).isEmpty();
    }

    // ---- MTI ------------------------------------------------------------------------------------------------------

    @Test
    void aResponseMtiIsTheRequestMtiWithTheFunctionDigitRaisedByOne() {
        assertThat(Mti.responseFor("0100")).isEqualTo("0110");
        assertThat(Mti.responseFor("0200")).isEqualTo("0210");
        assertThat(Mti.responseFor("0400")).isEqualTo("0410");
        assertThat(Mti.responseFor("0420")).isEqualTo("0430"); // an advice is answered too
        assertThat(Mti.responseFor("0800")).isEqualTo("0810");
        assertThat(Mti.responseFor("1240")).isEqualTo("1250");
        assertThatThrownBy(() -> Mti.responseFor("0110")).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> Mti.responseFor("01")).isInstanceOf(Iso8583Exception.class);
    }

    @Test
    void requestsAndAdvicesAreRequestsAndResponsesAreNot() {
        assertThat(Mti.isRequest("0100")).isTrue();
        assertThat(Mti.isRequest("0420")).isTrue();
        assertThat(Mti.isRequest("0110")).isFalse();
        assertThat(Mti.isRequest("0430")).isFalse();
        assertThat(Mti.isRequest("1240")).isTrue(); // a notification is sent, not answered
        assertThat(Mti.isRequest("1250")).isFalse();
    }

    @Test
    void anMtiIsDescribedInWords() {
        assertThat(Mti.describe("0800")).isEqualTo("ISO 8583:1987, Network management, Request, Acquirer");
        assertThat(Mti.describe("1240")).isEqualTo("ISO 8583:1993, Financial, Notification, Acquirer"); // a first presentment
        assertThat(Mti.describe("1250")).isEqualTo("ISO 8583:1993, Financial, Notification acknowledgement, Acquirer");
        assertThat(Mti.describe("0110")).contains("Authorization", "Request response");
    }

    @Test
    void aMessageCanBeCopiedAsItsOwnResponse() {
        Message request = purchase();
        Message response = request.copyWithMti(Mti.responseFor(request.mti())).set(39, "00");

        assertThat(response.mti()).isEqualTo("0210");
        assertThat(response.get(11)).isEqualTo("000123");
        assertThat(request.has(39)).isFalse(); // the request was not touched
    }

    // ---- TLV ------------------------------------------------------------------------------------------------------

    @Test
    void tlvParsesSingleAndMultiByteTags() {
        List<Tlv.Entry> entries = Tlv.parse("9F260811223344556677889F02060000000025005F2A020840");

        assertThat(entries).extracting(Tlv.Entry::tag).containsExactly("9F26", "9F02", "5F2A");
        assertThat(entries.get(0).valueHex()).isEqualTo("1122334455667788");
        assertThat(entries.get(2).valueHex()).isEqualTo("0840");
    }

    @Test
    void tlvParsesConstructedTagsIntoChildren() {
        String inner = Tlv.encode("9F26", "AABBCCDDEEFF0011") + Tlv.encode("82", "1980");
        List<Tlv.Entry> entries = Tlv.parse(Tlv.encode("77", inner));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).constructed()).isTrue();
        assertThat(entries.get(0).children()).extracting(Tlv.Entry::tag).containsExactly("9F26", "82");
    }

    @Test
    void tlvLongLengthFormsRoundTrip() {
        String value = "AB".repeat(200); // 200 bytes -> 0x81 0xC8
        String encoded = Tlv.encode("9F10", value);

        assertThat(encoded).startsWith("9F1081C8");
        assertThat(Tlv.parse(encoded).get(0).valueHex()).isEqualTo(value);
        String huge = "CD".repeat(300); // 300 bytes -> 0x82 0x01 0x2C
        assertThat(Tlv.encode("9F10", huge)).startsWith("9F1082012C");
        assertThat(Tlv.parse(Tlv.encode("9F10", huge)).get(0).valueHex()).isEqualTo(huge);
    }

    @Test
    void tlvRefusesTruncatedAndMalformedData() {
        for (String bad : new String[] { "9F", "9F26", "9F260811", "9F2608112233445566778", "9F2681", "9F268201", "9F26FF00", "XYZ", "9" }) {
            assertThatThrownBy(() -> Tlv.parse(bad)).as(bad).isInstanceOf(Iso8583Exception.class);
        }
        assertThat(Tlv.parse("")).isEmpty();
    }

    @Test
    void tlvNestingIsLimitedSoHostileDataCannotExhaustTheStack() {
        String nested = "9F1001AA";
        for (int i = 0; i < 20; i++) {
            nested = Tlv.encode("77", nested);
        }
        String hostile = nested;

        assertThatThrownBy(() -> Tlv.parse(hostile)).isInstanceOf(Iso8583Exception.class).hasMessageContaining("deeply");
    }
}
