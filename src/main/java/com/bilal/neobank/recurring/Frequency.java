package com.bilal.neobank.recurring;

import java.time.LocalDate;

public enum Frequency {
    WEEKLY,
    MONTHLY;

    /** The n-th scheduled date (0 = the start date). Always counted from the start, so month-end clamping never drifts. */
    public LocalDate occurrence(LocalDate start, int n) {
        return this == WEEKLY ? start.plusWeeks(n) : start.plusMonths(n);
    }
}
