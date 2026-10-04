package com.bilal.neobank.history;

import java.util.List;

/** Minimal RFC 4180 writer with protection against spreadsheet formula injection. */
final class CsvWriter {

    private final StringBuilder out = new StringBuilder();

    /** A text cell that may contain user input: neutralized so a spreadsheet never evaluates it as a formula. */
    static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        boolean looksLikeFormula = first == '=' || first == '+' || first == '-' || first == '@' || first == '\t'
                || first == '\r';
        return looksLikeFormula ? "'" + value : value;
    }

    void row(List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quote(cells.get(i)));
        }
        out.append("\r\n");
    }

    private static String quote(String cell) {
        if (cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0) {
            return '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
