package com.bilal.cardnet.iso8583;

/** A message that cannot be built, packed, read or framed. {@link #field()} is 0 when the problem is not about one field. */
public final class Iso8583Exception extends RuntimeException {

    private final int field;

    public Iso8583Exception(String message) {
        this(0, message);
    }

    public Iso8583Exception(int field, String message) {
        super(field == 0 ? message : "DE " + field + ": " + message);
        this.field = field;
    }

    public int field() {
        return field;
    }
}
