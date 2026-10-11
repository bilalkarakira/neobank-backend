package com.bilal.cardnet.iso8583.clearing;

/** A clearing file that cannot be written or that must be refused whole. {@link #reason()} is stable: acknowledgements carry it. */
public final class ClearingFileException extends RuntimeException {

    public enum Reason {
        /** shorter than the smallest possible file, or it does not start with the file magic */
        NOT_A_CLEARING_FILE,
        /** wrong key, wrong file name, or the bytes were changed (they are indistinguishable by design) */
        DECRYPT_FAILED,
        /** the first record is not a well formed file header */
        BAD_HEADER,
        /** the last record is not a well formed file trailer (or the file was cut off before it) */
        BAD_TRAILER,
        /** a record that is not a valid ISO 8583 message */
        BAD_RECORD,
        /** a record between header and trailer that is not a first presentment, or one with an illegal value */
        BAD_PRESENTMENT,
        /** a required data element is absent from a presentment */
        MISSING_ELEMENT,
        COUNT_MISMATCH,
        TOTAL_MISMATCH,
        HASH_MISMATCH,
        /** the same presentment key twice in one file */
        DUPLICATE_ITEM
    }

    private final Reason reason;

    public ClearingFileException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
