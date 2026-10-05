package com.bilal.cardnet.iso8583;

import com.bilal.cardnet.iso8583.FieldDef.Prefix;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Turns a {@link Message} into bytes and back, following docs/ISO8583-spec.md. Strict in both directions: a message that
 * is not exactly right is an {@link Iso8583Exception}, never a half-read object. Stateless and thread-safe.
 */
public final class Packager {

    private static final int HEX_BITMAP_CHARS = 16;

    private final Spec spec;

    public Packager(Spec spec) {
        this.spec = spec;
    }

    public Packager() {
        this(Spec.ISO87);
    }

    public Spec spec() {
        return spec;
    }

    // ---- pack -----------------------------------------------------------------------------------------------

    public byte[] pack(Message message) {
        boolean secondary = false;
        for (Map.Entry<Integer, String> entry : message.fields().entrySet()) {
            FieldDef def = spec.get(entry.getKey())
                    .orElseThrow(() -> new Iso8583Exception(entry.getKey(), "this data element is not defined"));
            def.validate(entry.getValue());
            secondary |= entry.getKey() > 64;
        }

        long primaryBits = secondary ? 1L << 63 : 0L;
        long secondaryBits = 0L;
        for (int number : message.fields().keySet()) {
            if (number <= 64) {
                primaryBits |= 1L << (64 - number);
            } else {
                secondaryBits |= 1L << (128 - number);
            }
        }

        StringBuilder out = new StringBuilder(message.mti());
        out.append(hex64(primaryBits));
        if (secondary) {
            out.append(hex64(secondaryBits));
        }
        for (Map.Entry<Integer, String> entry : message.fields().entrySet()) {
            FieldDef def = spec.get(entry.getKey()).orElseThrow();
            String value = entry.getValue();
            if (def.prefix() != Prefix.FIXED) {
                out.append(pad(value.length(), def.prefix().digits()));
            }
            out.append(value);
        }
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    // ---- unpack ---------------------------------------------------------------------------------------------

    public Message unpack(byte[] bytes) {
        if (bytes == null) {
            throw new Iso8583Exception("no message");
        }
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] < 0x20 || bytes[i] > 0x7E) {
                throw new Iso8583Exception("byte " + i + " is not printable ASCII");
            }
        }
        Reader in = new Reader(new String(bytes, StandardCharsets.US_ASCII));

        String mti = in.take(0, 4, "MTI");
        if (!FieldType.N.accepts(mti)) {
            throw new Iso8583Exception("the MTI must be 4 digits");
        }
        Message message = Message.of(mti);

        long primary = parseBitmap(in.take(0, HEX_BITMAP_CHARS, "primary bitmap"));
        long secondary = 0L;
        boolean hasSecondary = (primary & (1L << 63)) != 0;
        if (hasSecondary) {
            secondary = parseBitmap(in.take(0, HEX_BITMAP_CHARS, "secondary bitmap"));
            if ((secondary & (1L << 63)) != 0) {
                throw new Iso8583Exception(65, "a tertiary bitmap is not supported");
            }
        }

        for (int number = 2; number <= (hasSecondary ? 128 : 64); number++) {
            boolean set = number <= 64 ? (primary & (1L << (64 - number))) != 0 : (secondary & (1L << (128 - number))) != 0;
            if (!set) {
                continue;
            }
            FieldDef def = spec.get(number).orElseThrow(() -> new Iso8583Exception(0, "bitmap announces a data element this profile does not define"));
            String value = readField(in, def);
            def.validate(value);
            message.set(number, value);
        }
        if (!in.atEnd()) {
            throw new Iso8583Exception(in.remaining() + " unexpected byte(s) after the last data element");
        }
        return message;
    }

    private static String readField(Reader in, FieldDef def) {
        int length = def.length();
        if (def.prefix() != Prefix.FIXED) {
            String prefix = in.take(def.number(), def.prefix().digits(), "length prefix");
            if (!FieldType.N.accepts(prefix)) {
                throw new Iso8583Exception(def.number(), "the length prefix must be digits");
            }
            length = Integer.parseInt(prefix);
            if (length < 1 || length > def.length()) {
                throw new Iso8583Exception(def.number(), "length " + length + " is outside 1.." + def.length());
            }
        }
        return in.take(def.number(), length, "value");
    }

    private static long parseBitmap(String hex) {
        long bits = 0L;
        for (int i = 0; i < hex.length(); i++) {
            int digit = Character.digit(hex.charAt(i), 16);
            if (digit < 0) {
                throw new Iso8583Exception("the bitmap is not hexadecimal");
            }
            bits = (bits << 4) | digit;
        }
        return bits;
    }

    private static String hex64(long bits) {
        String hex = Long.toHexString(bits).toUpperCase();
        return "0".repeat(HEX_BITMAP_CHARS - hex.length()) + hex;
    }

    private static String pad(int number, int digits) {
        String text = Integer.toString(number);
        return "0".repeat(digits - text.length()) + text;
    }

    /** A cursor that refuses to read past the end instead of returning less than it was asked for. */
    private static final class Reader {
        private final String text;
        private int position;

        Reader(String text) {
            this.text = text;
        }

        String take(int field, int count, String what) {
            if (count > text.length() - position) {
                throw new Iso8583Exception(field, "the message ends inside the " + what + " (needs " + count
                        + " more characters, " + (text.length() - position) + " left)");
            }
            String part = text.substring(position, position + count);
            position += count;
            return part;
        }

        boolean atEnd() {
            return position == text.length();
        }

        int remaining() {
            return text.length() - position;
        }
    }
}
