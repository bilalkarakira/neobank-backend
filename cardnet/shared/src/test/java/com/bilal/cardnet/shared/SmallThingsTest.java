package com.bilal.cardnet.shared;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RetrySchedule, Pans and Json: small and pure. */
class SmallThingsTest {

    // ---- RetrySchedule ----

    @Test
    void theScheduleIsOneTwoFourEightSixteenThenEverySixtySeconds() {
        RetrySchedule schedule = RetrySchedule.standard();
        List<Long> seconds = java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(n -> schedule.delayAfter(n).toSeconds()).toList();
        assertThat(seconds).containsExactly(1L, 2L, 4L, 8L, 16L, 60L, 60L, 60L, 60L);
    }

    @Test
    void aScaleShrinksTheWaitsAndMustBePositive() {
        assertThat(new RetrySchedule(0.01).delayAfter(3)).isEqualTo(Duration.ofMillis(40));
        assertThat(new RetrySchedule(0.01).delayAfter(6)).isEqualTo(Duration.ofMillis(600));
        assertThatThrownBy(() -> new RetrySchedule(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrySchedule(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Pans ----

    @Test
    void aNumberIsShownAsFirstSixAndLastFourOnly() {
        assertThat(Pans.mask("9999000000000012")).isEqualTo("999900******0012");
        assertThat(Pans.mask("4111111111111")).isEqualTo("411111***1111");
        assertThat(Pans.mask("12345")).isEqualTo("*****");
        assertThat(Pans.mask(null)).isEmpty();
        assertThat(Pans.last4("9999000000000012")).isEqualTo("0012");
        assertThat(Pans.last4("12")).isEmpty();
    }

    @Test
    void luhnAcceptsRealTestNumbersAndRejectsAnySingleDigitChange() {
        for (String valid : List.of("4111111111111111", "5555555555554444", "9999000000000012")) {
            assertThat(Pans.luhnValid(valid)).as(valid).isTrue();
            for (int i = 0; i < valid.length(); i++) {
                String changed = valid.substring(0, i) + (char) ('0' + (valid.charAt(i) - '0' + 1) % 10) + valid.substring(i + 1);
                assertThat(Pans.luhnValid(changed)).as(changed).isFalse();
            }
        }
        assertThat(Pans.luhnValid("411111111111")).as("too short").isFalse();
        assertThat(Pans.luhnValid("4111 1111 1111 1111")).isFalse();
        assertThat(Pans.luhnValid(null)).isFalse();
    }

    // ---- Json ----

    @Test
    void writesNestedValuesWithEscaping() {
        Map<String, Object> inner = Json.object();
        inner.put("ok", true);
        inner.put("n", 5);
        inner.put("amount", new java.math.BigDecimal("12.50"));
        Map<String, Object> outer = Json.object();
        outer.put("text", "say \"hi\"\n\\ \u0001");
        outer.put("none", null);
        outer.put("list", List.of(1, "two", inner));

        assertThat(Json.write(outer)).isEqualTo(
                "{\"text\":\"say \\\"hi\\\"\\n\\\\ \\u0001\",\"none\":null,\"list\":[1,\"two\",{\"ok\":true,\"n\":5,\"amount\":12.50}]}");
    }

    @Test
    void readsOneFlatObject() {
        Map<String, Object> map = Json.parseFlat(" { \"issuerDown\" : true, \"delay\": 250, \"ratio\": 1.5, \"name\": \"a\\\"b\\u0041\", \"x\": null } ");

        assertThat(map).containsEntry("issuerDown", true).containsEntry("delay", 250L).containsEntry("ratio", 1.5)
                .containsEntry("name", "a\"bA").containsEntry("x", null);
        assertThat(Json.parseFlat("{}")).isEmpty();
    }

    @Test
    void refusesWhatIsNotAFlatObject() {
        for (String bad : new String[] { "", "[]", "{", "{\"a\":}", "{\"a\":1,}", "{\"a\":{\"b\":1}}", "{\"a\":[1]}", "{\"a\":1} x", "{a:1}", "{\"a\":\"x}", "null" }) {
            assertThatThrownBy(() -> Json.parseFlat(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Json.parseFlat(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
