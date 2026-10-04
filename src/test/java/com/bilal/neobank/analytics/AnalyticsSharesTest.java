package com.bilal.neobank.analytics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsSharesTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void sharesAlwaysAddUpToExactlyOneHundred() {
        // three equal thirds: naive rounding gives 33.3 x 3 = 99.9
        List<BigDecimal> thirds = AnalyticsService.shares(List.of(bd("10"), bd("10"), bd("10")), bd("30"));

        assertThat(thirds.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.0");
        assertThat(thirds).containsExactly(bd("33.4"), bd("33.3"), bd("33.3"));
    }

    @Test
    void largestRemainderGetsTheLeftoverTenth() {
        List<BigDecimal> shares = AnalyticsService.shares(List.of(bd("80"), bd("30"), bd("10")), bd("120"));

        assertThat(shares).containsExactly(bd("66.7"), bd("25.0"), bd("8.3"));
    }

    @Test
    void emptyAndZeroTotalsGiveZeroShares() {
        assertThat(AnalyticsService.shares(List.of(), BigDecimal.ZERO)).isEmpty();
        assertThat(AnalyticsService.shares(List.of(BigDecimal.ZERO), BigDecimal.ZERO)).containsExactly(bd("0.0"));
    }

    @Test
    void randomListsAlwaysSumToOneHundredAndStayWithinOneTenthOfTheExactShare() {
        Random random = new Random(7);
        for (int round = 0; round < 500; round++) {
            int n = 1 + random.nextInt(10);
            List<BigDecimal> values = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                BigDecimal value = BigDecimal.valueOf(1 + random.nextInt(100000), 2);
                values.add(value);
                total = total.add(value);
            }

            List<BigDecimal> shares = AnalyticsService.shares(values, total);

            assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.0");
            for (int i = 0; i < n; i++) {
                BigDecimal exact = values.get(i).multiply(bd("100")).divide(total, 6, java.math.RoundingMode.HALF_UP);
                assertThat(shares.get(i).subtract(exact).abs()).isLessThanOrEqualTo(bd("0.1"));
            }
        }
    }
}
