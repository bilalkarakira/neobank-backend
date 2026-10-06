package com.bilal.cardnet.iso8583.clearing;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException.Reason;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The content of one clearing file: who sent it to whom for which business date, and the first presentments (MTI 1240)
 * in it. Presentments are ordinary ISO 8583 messages: DE 2 card, DE 3 processing code (000000 purchase, 200000 refund),
 * DE 4 FINAL amount, and DE 11, 13, 32, 37, 41 of the original authorization (that is how the issuer finds it again).
 */
public record ClearingFile(String fileId, LocalDate businessDate, String sender, String receiver, String currency,
        List<Message> presentments) {

    public static final String PRESENTMENT = "1240";
    public static final String PURCHASE = "000000";
    public static final String REFUND = "200000";
    public static final int MAX_PRESENTMENTS = 100_000;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final int[] REQUIRED = {2, 3, 4, 7, 11, 12, 13, 32, 37, 41, 42, 49};

    public ClearingFile {
        requireName(fileId, "file id");
        requireName(sender, "sender");
        requireName(receiver, "receiver");
        if (businessDate == null) {
            throw new ClearingFileException(Reason.BAD_HEADER, "no business date");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new ClearingFileException(Reason.BAD_HEADER, "the currency must be three capital letters");
        }
        presentments = List.copyOf(presentments);
        if (presentments.size() > MAX_PRESENTMENTS) {
            throw new ClearingFileException(Reason.COUNT_MISMATCH, "more than " + MAX_PRESENTMENTS + " presentments");
        }
    }

    private static void requireName(String value, String what) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new ClearingFileException(Reason.BAD_HEADER, "the " + what + " must be 1-32 of letters, digits, . _ -");
        }
    }

    /** Sum of DE 4 in minor units over all presentments. */
    public long controlTotal() {
        long total = 0;
        for (Message m : presentments) {
            total += Long.parseLong(m.get(4));
        }
        return total;
    }

    /** Identifies a presentment inside one file; two with the same key are the same item sent twice. */
    public static String key(Message m) {
        return String.join("|", m.get(2), m.get(3), m.get(32), m.get(11), m.get(37), m.get(41), m.get(13));
    }

    /** Throws {@link ClearingFileException} unless the message is a well formed first presentment. */
    public static void validatePresentment(Message m) {
        if (!PRESENTMENT.equals(m.mti())) {
            throw new ClearingFileException(Reason.BAD_PRESENTMENT, "a record is MTI " + m.mti() + ", not " + PRESENTMENT);
        }
        for (int field : REQUIRED) {
            if (!m.has(field)) {
                throw new ClearingFileException(Reason.MISSING_ELEMENT, "presentment without DE " + field);
            }
        }
        String code = m.get(3);
        if (!PURCHASE.equals(code) && !REFUND.equals(code)) {
            throw new ClearingFileException(Reason.BAD_PRESENTMENT, "processing code " + code + " is neither purchase nor refund");
        }
        if (Long.parseLong(m.get(4)) <= 0) {
            throw new ClearingFileException(Reason.BAD_PRESENTMENT, "the amount must be positive");
        }
        if (PURCHASE.equals(code) && !m.has(38)) {
            throw new ClearingFileException(Reason.MISSING_ELEMENT, "purchase presentment without DE 38 (authorization code)");
        }
    }

    /** Validates every presentment and refuses a repeated key. */
    static void validateAll(List<Message> presentments) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Message m : presentments) {
            validatePresentment(m);
            if (!seen.add(key(m))) {
                throw new ClearingFileException(Reason.DUPLICATE_ITEM, "the same presentment appears twice in the file");
            }
        }
    }
}
