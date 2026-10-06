package com.bilal.neobank.card;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuhnAndCryptoTest {

    private static CardProperties properties(String key) {
        return new CardProperties(key, 3, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 10, Duration.ofMinutes(10),
                Duration.ofDays(7), 3, List.of(), true, new CardProperties.Listener(false, 0, 1, Duration.ofSeconds(1), "127.0.0.1"));
    }

    @Test
    void luhnAcceptsKnownValidNumbersAndRejectsEverySingleDigitChange() {
        // the classic test numbers published by card schemes and payment providers
        for (String valid : List.of("4111111111111111", "5555555555554444", "378282246310005", "79927398713")) {
            assertThat(Luhn.isValid(valid)).as(valid).isTrue();
            for (int i = 0; i < valid.length(); i++) {
                for (char d = '0'; d <= '9'; d++) {
                    if (d != valid.charAt(i)) {
                        String changed = valid.substring(0, i) + d + valid.substring(i + 1);
                        assertThat(Luhn.isValid(changed)).as(changed).isFalse();
                    }
                }
            }
        }
    }

    @Test
    void luhnRejectsJunk() {
        assertThat(Luhn.isValid(null)).isFalse();
        assertThat(Luhn.isValid("")).isFalse();
        assertThat(Luhn.isValid("0")).isFalse();
        assertThat(Luhn.isValid("4111 1111 1111 1111")).isFalse();
        assertThat(Luhn.isValid("41111111111111x1")).isFalse();
    }

    @Test
    void checkDigitCompletesTheNumber() {
        assertThat(Luhn.checkDigit("411111111111111")).isEqualTo(1);
        assertThat(Luhn.checkDigit("7992739871")).isEqualTo(3);
        for (int i = 0; i < 1000; i++) {
            String body = "999900" + "%09d".formatted(i * 7919L % 1_000_000_000L);
            assertThat(Luhn.isValid(body + Luhn.checkDigit(body))).isTrue();
        }
    }

    @Test
    void generatedPansUseTheTestBinAndPassLuhn() {
        for (int i = 0; i < 500; i++) {
            String pan = CardService.newPan();
            assertThat(pan).hasSize(16).startsWith("999900").matches("\\d{16}");
            assertThat(Luhn.isValid(pan)).isTrue();
        }
    }

    @Test
    void hashesAreKeyedDeterministicAndSeparatePansFromPins() {
        CardCrypto a = new CardCrypto(properties("a".repeat(32)));
        CardCrypto b = new CardCrypto(properties("b".repeat(32)));
        UUID card = UUID.randomUUID();

        assertThat(a.hashPan("9999000000000001")).hasSize(64).isEqualTo(a.hashPan("9999000000000001"));
        assertThat(a.hashPan("9999000000000001")).isNotEqualTo(a.hashPan("9999000000000002"));
        assertThat(a.hashPan("9999000000000001")).as("another key gives another hash").isNotEqualTo(b.hashPan("9999000000000001"));
        assertThat(a.hashPin(card, "1234")).isNotEqualTo(a.hashPin(UUID.randomUUID(), "1234"));
        assertThat(a.pinMatches(card, "1234", a.hashPin(card, "1234"))).isTrue();
        assertThat(a.pinMatches(card, "1235", a.hashPin(card, "1234"))).isFalse();
    }

    @Test
    void aShortKeyIsRefusedAtStartup() {
        assertThatThrownBy(() -> new CardCrypto(properties("too-short"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CardCrypto(properties(null))).isInstanceOf(IllegalStateException.class);
    }
}
