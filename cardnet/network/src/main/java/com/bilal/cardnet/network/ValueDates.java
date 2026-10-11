package com.bilal.cardnet.network;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Value dates of a settlement: T+1 business day. A business day is not a Saturday, not a Sunday and not a listed holiday. */
public final class ValueDates {

    private ValueDates() {
    }

    /** The first business day after {@code businessDate}. */
    public static LocalDate next(LocalDate businessDate, Set<LocalDate> holidays) {
        LocalDate day = businessDate.plusDays(1);
        while (!isBusinessDay(day, holidays)) {
            day = day.plusDays(1);
        }
        return day;
    }

    public static boolean isBusinessDay(LocalDate day, Set<LocalDate> holidays) {
        DayOfWeek weekday = day.getDayOfWeek();
        return weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY && !holidays.contains(day);
    }
}
