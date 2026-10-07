package com.bilal.cardnet.shared;

import com.bilal.cardnet.iso8583.Iso8583Exception;
import com.bilal.cardnet.iso8583.Message;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagesTest {

    private static Message payment() {
        return Message.of("0100").set(2, "9999000000000012").set(3, "000000").set(4, "000000004250").set(7, "1005120000")
                .set(11, "000501").set(12, "120000").set(13, "1005").set(14, "2910").set(18, "5814").set(22, "051")
                .set(32, "123456").set(37, "000000000501").set(41, "TERM0001").set(42, "MERCHANT0000001")
                .set(43, "%-40s".formatted("Corner Cafe")).set(49, "840").set(52, "0123456789ABCDEF");
    }

    @Test
    void responseMtiAddsTen() {
        assertThat(Messages.responseMti("0100")).isEqualTo("0110");
        assertThat(Messages.responseMti("0200")).isEqualTo("0210");
        assertThat(Messages.responseMti("0400")).isEqualTo("0410");
        assertThat(Messages.responseMti("0800")).isEqualTo("0810");
        assertThat(Messages.responseMti("0120")).isEqualTo("0130");
    }

    @Test
    void aResponseEchoesTheUsualElementsAndNothingSecret() {
        Message response = Messages.response(payment(), "51");

        assertThat(response.mti()).isEqualTo("0110");
        assertThat(response.get(39)).isEqualTo("51");
        for (int field : Messages.ECHOED) {
            assertThat(response.get(field)).as("DE " + field).isEqualTo(payment().get(field));
        }
        assertThat(response.has(52)).isFalse();
        assertThat(response.has(14)).isFalse();
        assertThat(response.has(43)).isFalse();
    }

    @Test
    void aRequestAndItsResponseShareOneKey() {
        Message request = payment();
        assertThat(Messages.responseKey(request)).isEqualTo("0110|000501|000000000501");
        assertThat(Messages.keyOfResponse(Messages.response(request, "00"))).isEqualTo(Messages.responseKey(request));
        Message network = Message.of("0800").set(11, "000009").set(70, "301");
        assertThat(Messages.responseKey(network)).isEqualTo("0810|000009|");
    }

    @Test
    void amountsAreMinorUnits() {
        assertThat(Messages.amount(payment())).isEqualByComparingTo("42.50");
        assertThat(Messages.amount(payment()).scale()).isEqualTo(2);
        assertThat(Messages.de4(new BigDecimal("42.50"))).isEqualTo("000000004250");
        assertThat(Messages.de4(new BigDecimal("0.01"))).isEqualTo("000000000001");
        assertThat(Messages.de4(new BigDecimal("42.5"))).isEqualTo("000000004250");
    }

    @Test
    void theAcquirerIsPaddedToElevenDigits() {
        assertThat(Messages.padAcquirer("123456")).isEqualTo("00000123456");
        assertThat(Messages.padAcquirer("1")).isEqualTo("00000000001");
        assertThat(Messages.padAcquirer("12345678901")).isEqualTo("12345678901");
    }

    @Test
    void de7IsUtcMonthDayTime() {
        assertThat(Messages.de7(Instant.parse("2026-10-05T18:07:09Z"))).isEqualTo("1005180709");
        assertThat(Messages.de7(Instant.parse("2027-01-02T00:00:01Z"))).isEqualTo("0102000001");
    }

    @Test
    void aReversalRepeatsTheOriginalAndNamesItInDe90() {
        Message reversal = Messages.reversalOf(payment(), "000777", "1005120500");

        assertThat(reversal.mti()).isEqualTo("0400");
        for (int field : new int[] { 2, 3, 4, 13, 37, 41, 42, 49, 18, 43 }) {
            assertThat(reversal.get(field)).as("DE " + field).isEqualTo(payment().get(field));
        }
        assertThat(reversal.get(7)).isEqualTo("1005120500");
        assertThat(reversal.get(11)).isEqualTo("000777");
        // original MTI 0100, STAN 000501, DE7 1005120000, acquirer 123456 padded to 11, forwarding institution 11 zeros
        assertThat(reversal.get(90)).isEqualTo("0100" + "000501" + "1005120000" + "00000123456" + "00000000000");
        assertThat(reversal.has(52)).as("never any PIN data").isFalse();
        assertThat(reversal.has(14)).isFalse();
    }

    @Test
    void de90IsReadBackExactly() {
        Messages.Original original = Messages.original(Messages.reversalOf(payment(), "000777", "1005120500"));

        assertThat(original).isEqualTo(new Messages.Original("0100", "000501", "1005120000", "00000123456"));
    }

    @Test
    void de90MustNameAPaymentRequest() {
        Message wrongType = Messages.reversalOf(payment(), "000777", "1005120500").set(90, "0800" + "000501" + "1005120000" + "00000123456" + "00000000000");
        assertThatThrownBy(() -> Messages.original(wrongType)).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> Messages.original(Message.of("0400").set(11, "000001"))).isInstanceOf(Iso8583Exception.class);
    }

    @Test
    void anAdviceIsTheRequestWithTheAuthCodeAndWithoutPinData() {
        Message advice = Messages.advice(payment(), "SI4F2K");

        assertThat(advice.mti()).isEqualTo("0120");
        assertThat(advice.get(38)).isEqualTo("SI4F2K");
        assertThat(advice.get(39)).isEqualTo("00");
        assertThat(advice.has(52)).isFalse();
        for (int field : new int[] { 2, 3, 4, 7, 11, 13, 14, 18, 32, 37, 41, 42, 49 }) {
            assertThat(advice.get(field)).as("DE " + field).isEqualTo(payment().get(field));
        }
        assertThat(payment().has(52)).as("the original is not changed").isTrue();
    }

    @Test
    void anAdviceNeverCarriesTheSecurityCodeOfAnOnlinePayment() {
        Message advice = Messages.advice(payment().set(22, "812").set(25, "59").set(53, "123"), "SI4F2K");

        assertThat(advice.has(53)).isFalse();
        assertThat(advice.has(52)).isFalse();
        assertThat(advice.get(22)).as("the rest of the request is kept").isEqualTo("812");
    }

    @Test
    void networkManagementMessagesCarryTheirCode() {
        assertThat(Messages.signOn("000001", "1005120000").get(70)).isEqualTo("001");
        assertThat(Messages.echo("000002", "1005120000").get(70)).isEqualTo("301");
        assertThat(Messages.signOff("000003", "1005120000").get(70)).isEqualTo("002");
        assertThat(Messages.signOn("000001", "1005120000").mti()).isEqualTo("0800");
    }

    @Test
    void hasAllChecksEveryElement() {
        assertThat(Messages.hasAll(payment(), 2, 3, 4)).isTrue();
        assertThat(Messages.hasAll(payment().remove(4), 2, 3, 4)).isFalse();
        assertThat(Messages.hasAll(payment(), 5)).isFalse(); // an element nobody set
    }
}
