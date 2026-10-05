package com.bilal.cardnet.iso8583;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** An ISO 8583 message: a type indicator and data elements by number. Values are the text exactly as carried on the wire. */
public final class Message {

    private final String mti;
    private final NavigableMap<Integer, String> fields = new TreeMap<>();

    private Message(String mti) {
        this.mti = mti;
    }

    public static Message of(String mti) {
        if (mti == null || mti.length() != 4 || !FieldType.N.accepts(mti)) {
            throw new Iso8583Exception("the MTI must be 4 digits, not '" + mti + "'");
        }
        return new Message(mti);
    }

    /** Sets a data element. Whether the VALUE is legal is checked when the message is packed. */
    public Message set(int field, String value) {
        if (field < 2 || field > 128 || field == 65) {
            throw new Iso8583Exception(field, "not a settable data element (2-128 except 65)");
        }
        fields.put(field, Objects.requireNonNull(value, "value"));
        return this;
    }

    public Message remove(int field) {
        fields.remove(field);
        return this;
    }

    public String mti() {
        return mti;
    }

    public boolean has(int field) {
        return fields.containsKey(field);
    }

    /** The value, or null when the element is absent. */
    public String get(int field) {
        return fields.get(field);
    }

    public Map<Integer, String> fields() {
        return Collections.unmodifiableMap(fields);
    }

    /** A copy with a new MTI and the same elements (typically to build a response from its request). */
    public Message copyWithMti(String newMti) {
        Message copy = Message.of(newMti);
        copy.fields.putAll(fields);
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Message m && mti.equals(m.mti) && fields.equals(m.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mti, fields);
    }

    /** Never prints the PAN or PIN data: a message ends up in logs and assertion failures. */
    @Override
    public String toString() {
        return Inspector.summary(this);
    }
}
