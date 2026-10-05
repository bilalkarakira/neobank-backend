package com.bilal.neobank.history;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

/**
 * A position in a sorted history: "continue after the row with this sort value and this entry id". Unlike a page
 * number it does not move when new rows arrive, and the database finds it through the index instead of counting past
 * every earlier row (for the date orders, which have an index; amount orders still sort the account's rows).
 *
 * It is opaque to clients (base64url of {@code sort|value|entryId}) and carries only values from a row of the account
 * being read; the query still restricts to that account, so a forged cursor can at most start somewhere else in the
 * caller's own history. It is tied to one sort order: the same value means something different in another.
 */
record HistoryCursor(HistorySort sort, String value, long entryId) {

    static HistoryCursor after(HistorySort sort, HistoryRow row) {
        String value = switch (sort) {
            case DATE_DESC, DATE_ASC -> row.createdAt().toString();
            case AMOUNT_DESC, AMOUNT_ASC -> row.amount().toPlainString();
        };
        return new HistoryCursor(sort, value, row.entryId());
    }

    String encode() {
        String raw = sort.parameter() + "|" + value + "|" + entryId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Empty for anything that is not a cursor this class produced. */
    static Optional<HistoryCursor> decode(String text) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3) {
                return Optional.empty();
            }
            HistorySort sort = HistorySort.parse(parts[0]).orElse(null);
            if (sort == null) {
                return Optional.empty();
            }
            HistoryCursor cursor = new HistoryCursor(sort, parts[1], Long.parseLong(parts[2]));
            cursor.sortValue(); // the value must parse for this sort
            return Optional.of(cursor);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The value as the type the column has, for the query. */
    Object sortValue() {
        return switch (sort) {
            case DATE_DESC, DATE_ASC -> OffsetDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
            case AMOUNT_DESC, AMOUNT_ASC -> new BigDecimal(value);
        };
    }
}
