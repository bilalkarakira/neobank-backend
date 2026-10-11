package com.bilal.cardnet.iso8583.clearing;

import com.bilal.cardnet.iso8583.clearing.ClearingFileException.Reason;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The answer to a clearing FILE (the file is acknowledged as a whole, not message by message): accepted or refused, and
 * for an accepted file one result per presentment, numbered as they are in the file (1, 2, ...).
 *
 * <p>Plain text: {@code ACKHDR|fileId|ACCEPTED or REJECTED|reason}, then {@code ITEM|n|RESULT|reason} lines, then
 * {@code ACKEND|count}. Encrypted like a clearing file, with its own magic, by {@link ClearingFileCodec}.
 */
public record ClearingAck(String fileId, Status status, String reason, List<Item> items) {

    public enum Status {
        ACCEPTED, REJECTED
    }

    /** One presentment's result. {@code result} is a stable code such as POSTED or REFUSED. */
    public record Item(int record, String result, String reason) {
        public Item {
            if (record < 1 || result == null || !RESULT.matcher(result).matches()) {
                throw new ClearingFileException(Reason.BAD_RECORD, "an acknowledgement item needs a record number and a result code");
            }
            reason = clean(reason);
        }

        /** True when the presentment is settled as far as the acquirer is concerned. */
        public boolean cleared() {
            return CLEARED.contains(result);
        }
    }

    /** Results that mean the item is done: the issuer posted it, or had already captured it, or a dispute item was accepted. */
    public static final Set<String> CLEARED = Set.of("POSTED", "FORCE_POSTED", "REFUND_POSTED", "DUPLICATE", "CHARGEBACK_ACCEPTED", "REPRESENTMENT_ACCEPTED");

    private static final Pattern RESULT = Pattern.compile("[A-Z_]{1,24}");
    private static final int MAX_REASON = 120;

    public ClearingAck {
        if (fileId == null || fileId.isBlank() || status == null) {
            throw new ClearingFileException(Reason.BAD_HEADER, "an acknowledgement needs a file id and a status");
        }
        reason = clean(reason);
        items = List.copyOf(items);
        if (status == Status.REJECTED && !items.isEmpty()) {
            throw new ClearingFileException(Reason.BAD_RECORD, "a refused file has no item results");
        }
    }

    public static ClearingAck accepted(String fileId, List<Item> items) {
        return new ClearingAck(fileId, Status.ACCEPTED, "", items);
    }

    public static ClearingAck rejected(String fileId, String reason) {
        return new ClearingAck(fileId, Status.REJECTED, reason, List.of());
    }

    /** The result of presentment number {@code record}, or null when the acknowledgement does not mention it. */
    public Item item(int record) {
        for (Item item : items) {
            if (item.record() == record) {
                return item;
            }
        }
        return null;
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(c >= 0x20 && c <= 0x7E && c != '|' ? c : ' ');
        }
        String result = out.toString().strip();
        return result.length() > MAX_REASON ? result.substring(0, MAX_REASON) : result;
    }

    byte[] plainText() {
        StringBuilder out = new StringBuilder();
        out.append("ACKHDR|").append(fileId).append('|').append(status).append('|').append(reason).append('\n');
        for (Item item : items) {
            out.append("ITEM|").append(item.record()).append('|').append(item.result()).append('|').append(item.reason()).append('\n');
        }
        out.append("ACKEND|").append(items.size()).append('\n');
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    static ClearingAck parse(byte[] plain) {
        for (byte b : plain) {
            if (b != '\n' && (b < 0x20 || b > 0x7E)) {
                throw new ClearingFileException(Reason.BAD_RECORD, "the acknowledgement contains a byte that is not printable ASCII");
            }
        }
        String text = new String(plain, StandardCharsets.US_ASCII);
        if (!text.endsWith("\n")) {
            throw new ClearingFileException(Reason.BAD_TRAILER, "the acknowledgement does not end with a complete record");
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length < 2) {
            throw new ClearingFileException(Reason.BAD_HEADER, "the acknowledgement needs a header and an end record");
        }
        String[] header = lines[0].split("\\|", -1);
        if (header.length != 4 || !"ACKHDR".equals(header[0])) {
            throw new ClearingFileException(Reason.BAD_HEADER, "expected an ACKHDR record");
        }
        Status status;
        try {
            status = Status.valueOf(header[2]);
        } catch (IllegalArgumentException e) {
            throw new ClearingFileException(Reason.BAD_HEADER, "the status is neither ACCEPTED nor REJECTED");
        }
        String[] end = lines[lines.length - 1].split("\\|", -1);
        if (end.length != 2 || !"ACKEND".equals(end[0])) {
            throw new ClearingFileException(Reason.BAD_TRAILER, "expected an ACKEND record");
        }
        int count = lines.length - 2;
        if (!Integer.toString(count).equals(end[1])) {
            throw new ClearingFileException(Reason.COUNT_MISMATCH, "ACKEND says " + end[1] + " items, the file has " + count);
        }
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String[] parts = lines[i].split("\\|", -1);
            if (parts.length != 4 || !"ITEM".equals(parts[0])) {
                throw new ClearingFileException(Reason.BAD_RECORD, "expected an ITEM record on line " + (i + 1));
            }
            int record;
            try {
                record = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new ClearingFileException(Reason.BAD_RECORD, "bad record number on line " + (i + 1));
            }
            if (!Integer.toString(record).equals(parts[1])) {
                throw new ClearingFileException(Reason.BAD_RECORD, "bad record number on line " + (i + 1));
            }
            items.add(new Item(record, parts[2], parts[3]));
        }
        return new ClearingAck(header[1], status, header[3], items);
    }
}
