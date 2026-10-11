package com.bilal.cardnet.iso8583;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DE 53, the security code (CVV2) of an online payment: carried like any element, masked like a secret. */
class SecurityCodeTest {

    private final Packager packager = new Packager();

    private static Message online(String code) {
        return Message.of("0100").set(2, "9999000000000012").set(3, "000000").set(4, "000000001999").set(7, "1005120000").set(11, "000123")
                .set(12, "120000").set(13, "1005").set(14, "2910").set(18, "5732").set(22, "812").set(25, "59").set(32, "123456")
                .set(37, "000000000123").set(41, "TERM0001").set(42, "MERCHANT0000003").set(49, "840").set(53, code);
    }

    @Test
    void aThreeOrFourDigitCodeRoundTrips() {
        for (String code : new String[] {"123", "0007", "999", "000"}) {
            Message back = packager.unpack(packager.pack(online(code)));
            assertThat(back.get(53)).as(code).isEqualTo(code);
            assertThat(back).isEqualTo(online(code));
        }
    }

    @Test
    void itIsOnTheWireAsALengthAndDigits() {
        String wire = new String(packager.pack(online("123")), StandardCharsets.US_ASCII);
        assertThat(wire).contains("03123");
    }

    @Test
    void anythingButOneToFourDigitsIsRefused() {
        for (String bad : new String[] {"12345", "12a", "", " 12", "1 2", "ABC"}) {
            assertThatThrownBy(() -> packager.pack(online(bad))).as("'" + bad + "'").isInstanceOf(Iso8583Exception.class);
        }
    }

    @Test
    void theElementIsMarkedSensitiveAndNeverPrintedUnlessAskedFor() {
        assertThat(Spec.ISO87.get(53).orElseThrow().sensitive()).isTrue();

        String report = Inspector.format(online("123"));
        String line = report.lines().filter(l -> l.contains("Card verification value 2")).findFirst().orElseThrow();
        assertThat(line).endsWith("***").doesNotContain("123");
        assertThat(report).as("the number is masked as well").contains("999900******0012");
        assertThat(Inspector.format(online("4567"), Spec.ISO87, true)).as("only when the caller asks").contains("4567");
        assertThat(Inspector.format(online("4567"))).doesNotContain("4567");
    }

    @Test
    void theSummaryNeverShowsAValue() {
        assertThat(Inspector.summary(online("123"))).contains("53").doesNotContain("123");
    }
}
