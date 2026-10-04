package com.bilal.neobank.history;

import com.bilal.neobank.ledger.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** A validated history query. Dates are UTC and inclusive; amounts filter on the absolute value. */
public record HistoryFilter(LocalDate from, LocalDate to, TransactionType type, Direction direction,
        BigDecimal minAmount, BigDecimal maxAmount, String q, HistorySort sort, int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    private static final int MAX_PAGE = 100_000;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    /** Validates the raw request parameters; throws {@link InvalidFilterException} listing every problem. */
    public static HistoryFilter of(LocalDate from, LocalDate to, TransactionType type, Direction direction,
            BigDecimal minAmount, BigDecimal maxAmount, String q, String sort, Integer page, Integer size) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (from != null && to != null && from.isAfter(to)) {
            errors.put("from", "must not be after 'to'");
        }
        if (minAmount != null && (minAmount.signum() < 0 || minAmount.compareTo(MAX_AMOUNT) > 0)) {
            errors.put("minAmount", "must be between 0 and 1000000000");
        }
        if (maxAmount != null && (maxAmount.signum() < 0 || maxAmount.compareTo(MAX_AMOUNT) > 0)) {
            errors.put("maxAmount", "must be between 0 and 1000000000");
        }
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
            errors.put("minAmount", "must not be greater than maxAmount");
        }
        String query = q == null || q.isBlank() ? null : q.trim();
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            errors.put("q", "must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        HistorySort order = HistorySort.DATE_DESC;
        if (sort != null) {
            var parsed = HistorySort.parse(sort);
            if (parsed.isPresent()) {
                order = parsed.get();
            } else {
                errors.put("sort", "must be one of date,desc date,asc amount,desc amount,asc");
            }
        }
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageNumber < 0 || pageNumber > MAX_PAGE) {
            errors.put("page", "must be between 0 and " + MAX_PAGE);
        }
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            errors.put("size", "must be between 1 and " + MAX_SIZE);
        }

        if (!errors.isEmpty()) {
            throw new InvalidFilterException(errors);
        }
        return new HistoryFilter(from, to, type, direction, minAmount, maxAmount, query, order, pageNumber, pageSize);
    }
}
