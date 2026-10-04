package com.bilal.neobank.recurring;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FrequencyTest {

    @Test
    void weeklyAddsSevenDaysEachTime() {
        LocalDate start = LocalDate.of(2026, 12, 28);
        assertThat(Frequency.WEEKLY.occurrence(start, 0)).isEqualTo(start);
        assertThat(Frequency.WEEKLY.occurrence(start, 1)).isEqualTo(LocalDate.of(2027, 1, 4));
        assertThat(Frequency.WEEKLY.occurrence(start, 5)).isEqualTo(LocalDate.of(2027, 2, 1));
    }

    @Test
    void monthlyFromTheThirtyFirstClampsToShortMonthsAndComesBack() {
        LocalDate start = LocalDate.of(2026, 1, 31);
        assertThat(Frequency.MONTHLY.occurrence(start, 1)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Frequency.MONTHLY.occurrence(start, 2)).isEqualTo(LocalDate.of(2026, 3, 31)); // no drift to the 28th
        assertThat(Frequency.MONTHLY.occurrence(start, 3)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(Frequency.MONTHLY.occurrence(start, 13)).isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @Test
    void monthlyOnFebruary29ReturnsToItInLeapYears() {
        LocalDate start = LocalDate.of(2028, 2, 29);
        assertThat(Frequency.MONTHLY.occurrence(start, 12)).isEqualTo(LocalDate.of(2029, 2, 28));
        assertThat(Frequency.MONTHLY.occurrence(start, 48)).isEqualTo(LocalDate.of(2032, 2, 29));
    }
}
