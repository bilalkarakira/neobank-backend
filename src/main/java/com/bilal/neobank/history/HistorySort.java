package com.bilal.neobank.history;

import java.util.Optional;

/**
 * The allow-list of sort orders. The SQL fragment comes from here, never from the request, so a hostile
 * {@code sort} value cannot inject SQL. Every order ends with the entry id so rows never shuffle between pages.
 */
public enum HistorySort {
    DATE_DESC("date,desc", "e.created_at desc, e.id desc"),
    DATE_ASC("date,asc", "e.created_at asc, e.id asc"),
    AMOUNT_DESC("amount,desc", "e.amount desc, e.id desc"),
    AMOUNT_ASC("amount,asc", "e.amount asc, e.id asc");

    private final String parameter;
    private final String orderBy;

    HistorySort(String parameter, String orderBy) {
        this.parameter = parameter;
        this.orderBy = orderBy;
    }

    public String orderBy() {
        return orderBy;
    }

    public static Optional<HistorySort> parse(String value) {
        for (HistorySort sort : values()) {
            if (sort.parameter.equals(value)) {
                return Optional.of(sort);
            }
        }
        return Optional.empty();
    }
}
