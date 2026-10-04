package com.bilal.neobank.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Money is always a decimal string; shares and percentage changes are ratios, so plain numbers. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    /** Totals for one UTC calendar month ("2026-10"). */
    public record MonthTotals(String month, String income, String spending, String net) {
    }

    /** Percentage change versus the previous month; null when the previous value was zero (undefined). */
    public record Changes(BigDecimal income, BigDecimal spending, BigDecimal net) {
    }

    public record Summary(String currency, String totalBalance, MonthTotals thisMonth, MonthTotals lastMonth,
            Changes changePercent) {
    }

    public record BalancePoint(LocalDate date, String balance) {
    }

    public record CashflowMonth(String month, String income, String spending) {
    }

    /** {@code share} is a percentage with one decimal; the shares of a response add up to exactly 100.0. */
    public record CategoryShare(String name, String total, BigDecimal share) {
    }

    public record SpendingByCategory(LocalDate from, LocalDate to, String total, java.util.List<CategoryShare> categories) {
    }
}
