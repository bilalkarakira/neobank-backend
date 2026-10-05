package com.bilal.cardnet.iso8583;

/** The character class of a data element (see docs/ISO8583-spec.md). */
public enum FieldType {
    /** Digits only. */
    N,
    /** Letters and digits. */
    AN,
    /** Any printable ASCII character. */
    ANS,
    /** Binary data, carried as hex text (two characters per byte). */
    B;

    /** Whether the value may appear in a field of this type (length is checked separately). */
    public boolean accepts(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = switch (this) {
                case N -> c >= '0' && c <= '9';
                case AN -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
                case ANS -> c >= 0x20 && c <= 0x7E;
                case B -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
