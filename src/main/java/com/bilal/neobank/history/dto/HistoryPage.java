package com.bilal.neobank.history.dto;

import java.util.List;

public record HistoryPage(List<TransactionItem> items, int page, int size, long totalElements, int totalPages,
        String nextCursor) {
}
