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
 *
 * <p>A file of the DISPUTE family (name ending {@code .dsp}) carries instead chargebacks (MTI 1442, DE 48 = {@code CB|REASON|note})
 * and representments (MTI 1240 with DE 48 = {@code REP|note}), which repeat the identifiers of the disputed payment. A clearing
 * file ({@code .clr}) never holds those and a dispute file never holds a purchase or a refund.
 */
public record ClearingFile(String fileId, LocalDate businessDate, String sender, String receiver, String currency,
        List<Message> presentments) {

    public static final String PRESENTMENT = "1240";
    public static final String CHARGEBACK_MTI = "1442";
    /** What a customer can say is wrong; the note after it is free text. */
    public static final java.util.Set<String> DISPUTE_REASONS = java.util.Set.of("UNAUTHORIZED", "NOT_RECEIVED", "DUPLICATE", "WRONG_AMOUNT", "OTHER");
    public static final int MAX_NOTE = 200;

    /** What one record is. */
    public enum RecordKind {
        PURCHASE, REFUND, CHARGEBACK, REPRESENTMENT;

        /** True for the kinds that live in dispute files. */
        public boolean isDispute() {
            return this == CHARGEBACK || this == REPRESENTMENT;
        }
    }
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

    /** What kind of record a message is (it is not checked here: see {@link #validatePresentment}). */
    public static RecordKind kindOf(Message m) {
        if (CHARGEBACK_MTI.equals(m.mti())) {
            return RecordKind.CHARGEBACK;
        }
        String text = m.get(48);
        if (text != null && text.startsWith("REP|")) {
            return RecordKind.REPRESENTMENT;
        }
        return REFUND.equals(m.get(3)) ? RecordKind.REFUND : RecordKind.PURCHASE;
    }

    /** The reason of a chargeback ({@code CB|REASON|note}), or null. */
    public static String reasonOf(Message m) {
        if (kindOf(m) != RecordKind.CHARGEBACK || !m.has(48)) {
            return null;
        }
        String[] parts = m.get(48).split("\\|", -1);
        return parts.length > 1 ? parts[1] : null;
    }

    /** The note of a chargeback or a representment (the text after the last bar), or null for other records. */
    public static String noteOf(Message m) {
        RecordKind kind = kindOf(m);
        if (!kind.isDispute() || !m.has(48)) {
            return null;
        }
        String[] parts = m.get(48).split("\\|", -1);
        return parts[parts.length - 1];
    }

    /** Identifies a presentment inside one file; two with the same key are the same item sent twice. */
    public static String key(Message m) {
        return String.join("|", kindOf(m).name(), m.get(2), m.get(3), m.get(32), m.get(11), m.get(37), m.get(41), m.get(13));
    }

    /** Throws {@link ClearingFileException} unless the message is a well formed first presentment, chargeback or representment. */
    public static void validatePresentment(Message m) {
        if (!PRESENTMENT.equals(m.mti()) && !CHARGEBACK_MTI.equals(m.mti())) {
            throw new ClearingFileException(Reason.BAD_PRESENTMENT, "a record is MTI " + m.mti() + ", not " + PRESENTMENT + " or " + CHARGEBACK_MTI);
        }
        RecordKind kind = kindOf(m);
        if (kind.isDispute()) {
            validateDispute(m, kind);
            return;
        }
        if (m.has(48)) {
            throw new ClearingFileException(Reason.BAD_PRESENTMENT, "a first presentment has no DE 48");
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

    private static void validateDispute(Message m, RecordKind kind) {
        for (int field : REQUIRED) {
            if (!m.has(field)) {
                throw new ClearingFileException(Reason.MISSING_ELEMENT, kind.name().toLowerCase() + " without DE " + field);
            }
        }
        if (!m.has(48)) {
            throw new ClearingFileException(Reason.MISSING_ELEMENT, kind.name().toLowerCase() + " without DE 48 (the reason and note)");
        }
        if (!PURCHASE.equals(m.get(3))) {
            throw new ClearingFileException(Reason.BAD_PRESENTMENT, "a " + kind.name().toLowerCase() + " carries processing code 000000, not " + m.get(3));
        }
        if (Long.parseLong(m.get(4)) <= 0) {
            throw new ClearingFileException(Reason.BAD_PRESENTMENT, "the amount must be positive");
        }
        String[] parts = m.get(48).split("\\|", -1);
        if (kind == RecordKind.CHARGEBACK) {
            if (parts.length != 3 || !"CB".equals(parts[0]) || !DISPUTE_REASONS.contains(parts[1])) {
                throw new ClearingFileException(Reason.BAD_PRESENTMENT, "DE 48 of a chargeback must be CB|REASON|note with REASON one of " + new java.util.TreeSet<>(DISPUTE_REASONS));
            }
        } else if (parts.length != 2 || parts[1].isEmpty()) {
            throw new ClearingFileException(Reason.BAD_PRESENTMENT, "DE 48 of a representment must be REP|note with a note");
        }
        String note = parts[parts.length - 1];
        if (note.length() > MAX_NOTE) {
            throw new ClearingFileException(Reason.BAD_PRESENTMENT, "the note is longer than " + MAX_NOTE + " characters");
        }
    }

    /** A clearing file (.clr) holds no dispute record and a dispute file (.dsp) nothing else. Other names are not checked. */
    static void requireKindsMatchName(java.util.List<Message> presentments, String fileName) {
        boolean dispute = fileName.endsWith(".dsp");
        boolean clearing = fileName.endsWith(".clr");
        for (Message m : presentments) {
            boolean isDispute = kindOf(m).isDispute();
            if (clearing && isDispute) {
                throw new ClearingFileException(Reason.BAD_PRESENTMENT, "a " + kindOf(m).name().toLowerCase() + " does not belong in a clearing file");
            }
            if (dispute && !isDispute) {
                throw new ClearingFileException(Reason.BAD_PRESENTMENT, "a " + kindOf(m).name().toLowerCase() + " does not belong in a dispute file");
            }
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
