package com.bilal.cardnet.iso8583;

/**
 * One data element's definition.
 *
 * @param prefix how the length is carried: FIXED (none), LL (2 digits) or LLL (3 digits)
 * @param length the exact length for FIXED, the maximum for LL and LLL (in characters; for B, hex characters)
 * @param sensitive true for data that must never be printed in clear by default (PAN, PIN)
 */
public record FieldDef(int number, String name, FieldType type, Prefix prefix, int length, boolean sensitive) {

    public enum Prefix {
        FIXED(0), LL(2), LLL(3);

        private final int digits;

        Prefix(int digits) {
            this.digits = digits;
        }

        public int digits() {
            return digits;
        }
    }

    public FieldDef(int number, String name, FieldType type, Prefix prefix, int length) {
        this(number, name, type, prefix, length, false);
    }

    /** Throws unless the value is a legal value for this element. */
    public void validate(String value) {
        if (value == null) {
            throw new Iso8583Exception(number, "value is missing");
        }
        if (prefix == Prefix.FIXED) {
            if (value.length() != length) {
                throw new Iso8583Exception(number, "must be exactly " + length + " characters, not " + value.length());
            }
        } else {
            if (value.isEmpty()) {
                throw new Iso8583Exception(number, "must not be empty");
            }
            if (value.length() > length) {
                throw new Iso8583Exception(number, "at most " + length + " characters, not " + value.length());
            }
        }
        if (type == FieldType.B && value.length() % 2 != 0) {
            throw new Iso8583Exception(number, "binary data needs an even number of hex characters");
        }
        if (!type.accepts(value)) {
            throw new Iso8583Exception(number, "contains characters that are not allowed in a " + type.name().toLowerCase() + " field");
        }
    }
}
