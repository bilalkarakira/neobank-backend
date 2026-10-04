package com.bilal.neobank.interest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InterestCalculatorTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void monthlyRateIsTheTwelfthRootOfOnePlusApyMinusOne() {
        BigDecimal rate = InterestCalculator.monthlyRate(bd("0.035"));

        assertThat(rate).isCloseTo(bd("0.0028709"), within(bd("0.0000001")));
        assertThat(rate.scale()).isEqualTo(10);
        assertThat(InterestCalculator.monthlyRate(BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void twelveMonthlyCreditsCompoundToTheStatedApy() {
        BigDecimal rate = InterestCalculator.monthlyRate(bd("0.035"));
        BigDecimal balance = bd("1000.00");

        for (int month = 0; month < 12; month++) {
            balance = balance.add(InterestCalculator.interest(balance, rate));
        }

        // 3.5% of 1000 is 35.00, give or take the cent rounding of twelve credits
        assertThat(balance).isBetween(bd("1034.95"), bd("1035.05"));
    }

    @Test
    void averageIsTheMeanOfTheDailyBalances() {
        assertThat(InterestCalculator.averageBalance(List.of(bd("100"), bd("200"), bd("300"))))
                .isEqualByComparingTo("200.0000");
        assertThat(InterestCalculator.averageBalance(List.of(bd("0"), bd("0"), bd("100")))).isEqualByComparingTo("33.3333");
        assertThat(InterestCalculator.averageBalance(List.of())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aBalanceHeldForOnlyPartOfTheMonthEarnsProportionally() {
        // 1000 for 15 days then 1500 for 15 days -> 1250 on average
        List<BigDecimal> days = new java.util.ArrayList<>();
        for (int d = 1; d <= 15; d++) days.add(bd("1000"));
        for (int d = 16; d <= 30; d++) days.add(bd("1500"));

        BigDecimal average = InterestCalculator.averageBalance(days);

        assertThat(average).isEqualByComparingTo("1250.0000");
        assertThat(InterestCalculator.interest(average, InterestCalculator.monthlyRate(bd("0.035"))))
                .isEqualByComparingTo("3.59");
    }

    @Test
    void interestIsRoundedHalfUpToTheCentAndTinyAmountsRoundToZero() {
        assertThat(InterestCalculator.interest(bd("1.0000"), bd("0.0050000000"))).isEqualByComparingTo("0.01");
        assertThat(InterestCalculator.interest(bd("0.9999"), bd("0.0050000000"))).isEqualByComparingTo("0.00");
        assertThat(InterestCalculator.interest(bd("1.0000"), InterestCalculator.monthlyRate(bd("0.035"))))
                .isEqualByComparingTo("0.00");
        assertThat(InterestCalculator.interest(BigDecimal.ZERO, bd("0.0028709000"))).isEqualByComparingTo("0.00");
    }
}
