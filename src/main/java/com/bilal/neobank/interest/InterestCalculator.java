package com.bilal.neobank.interest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The interest formulas, as pure functions.
 *
 * <pre>
 *   monthly rate = (1 + APY)^(1/12) - 1          so that 12 monthly credits compound to exactly the APY
 *   average      = mean of the end-of-day balances of the days of the month
 *   interest     = round(average x monthly rate, 2)
 * </pre>
 *
 * The average daily balance (not the month-end balance) is used so that a deposit made on the 30th and withdrawn on
 * the 1st cannot collect a whole month of interest.
 */
public final class InterestCalculator {

    private InterestCalculator() {
    }

    /** The rate is a ratio, not money, so it is derived with floating point once and then fixed to 10 decimals. */
    public static BigDecimal monthlyRate(BigDecimal apy) {
        double rate = Math.pow(1.0 + apy.doubleValue(), 1.0 / 12.0) - 1.0;
        return BigDecimal.valueOf(rate).setScale(10, RoundingMode.HALF_UP);
    }

    /** Mean of the daily balances, to 4 decimals; zero when there are no days. */
    public static BigDecimal averageBalance(List<BigDecimal> endOfDayBalances) {
        if (endOfDayBalances.isEmpty()) {
            return BigDecimal.ZERO.setScale(4);
        }
        BigDecimal sum = endOfDayBalances.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(endOfDayBalances.size()), 4, RoundingMode.HALF_UP);
    }

    /** Interest in whole cents, rounded half-up. */
    public static BigDecimal interest(BigDecimal averageBalance, BigDecimal monthlyRate) {
        return averageBalance.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
    }
}
