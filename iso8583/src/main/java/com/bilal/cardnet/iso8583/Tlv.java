package com.bilal.cardnet.iso8583;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BER-TLV as used in the chip data of DE 55 (EMV). Tags are 1 or more bytes (a tag whose low five bits are all ones
 * continues in the next byte), lengths are short (below 128) or 0x81 / 0x82 long forms. Values are kept as hex text.
 * A tag with bit 6 set is constructed: its value is itself a list of TLVs and is parsed into {@link Entry#children()}.
 */
public final class Tlv {

    public record Entry(String tag, String valueHex, List<Entry> children) {
        public boolean constructed() {
            return !children.isEmpty();
        }
    }

    /** A few well-known EMV tags, for the inspector. */
    public static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("82", "Application interchange profile"),
            Map.entry("84", "Dedicated file name"),
            Map.entry("95", "Terminal verification results"),
            Map.entry("9A", "Transaction date"),
            Map.entry("9C", "Transaction type"),
            Map.entry("5F2A", "Transaction currency code"),
            Map.entry("9F02", "Amount, authorised"),
            Map.entry("9F10", "Issuer application data"),
            Map.entry("9F26", "Application cryptogram"),
            Map.entry("9F27", "Cryptogram information data"),
            Map.entry("9F36", "Application transaction counter"),
            Map.entry("9F37", "Unpredictable number"));

    private static final int MAX_DEPTH = 4;

    private Tlv() {
    }

    public static List<Entry> parse(String hex) {
        if (hex == null || hex.length() % 2 != 0 || !FieldType.B.accepts(hex.toUpperCase())) {
            throw new Iso8583Exception("TLV data must be an even number of hex characters");
        }
        return parse(hexToBytes(hex.toUpperCase()), 0, hex.length() / 2, 0);
    }

    /** One element: tag and value as hex, with the length computed. */
    public static String encode(String tagHex, String valueHex) {
        int length = valueHex.length() / 2;
        String lengthHex = length < 0x80 ? String.format("%02X", length)
                : length <= 0xFF ? String.format("81%02X", length) : String.format("82%04X", length);
        return tagHex.toUpperCase() + lengthHex + valueHex.toUpperCase();
    }

    private static List<Entry> parse(byte[] data, int from, int to, int depth) {
        if (depth > MAX_DEPTH) {
            throw new Iso8583Exception("TLV nested too deeply");
        }
        List<Entry> entries = new ArrayList<>();
        int i = from;
        while (i < to) {
            int tagStart = i;
            boolean constructed = (data[i] & 0x20) != 0;
            if ((data[i] & 0x1F) == 0x1F) {
                i++;
                while (true) {
                    if (i >= to) {
                        throw new Iso8583Exception("TLV ends inside a tag");
                    }
                    boolean more = (data[i] & 0x80) != 0;
                    i++;
                    if (!more) {
                        break;
                    }
                }
            } else {
                i++;
            }
            String tag = toHex(data, tagStart, i);
            if (i >= to) {
                throw new Iso8583Exception("TLV tag " + tag + " has no length");
            }
            int length = data[i++] & 0xFF;
            if (length >= 0x80) {
                int bytes = length & 0x7F;
                if (bytes < 1 || bytes > 2 || i + bytes > to) {
                    throw new Iso8583Exception("TLV tag " + tag + " has an unsupported or truncated length");
                }
                length = 0;
                for (int k = 0; k < bytes; k++) {
                    length = (length << 8) | (data[i++] & 0xFF);
                }
            }
            if (length > to - i) {
                throw new Iso8583Exception("TLV tag " + tag + " claims " + length + " bytes but only " + (to - i) + " remain");
            }
            List<Entry> children = constructed && length > 0 ? parse(data, i, i + length, depth + 1) : List.of();
            entries.add(new Entry(tag, toHex(data, i, i + length), children));
            i += length;
        }
        return entries;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    private static String toHex(byte[] data, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            out.append(String.format("%02X", data[i] & 0xFF));
        }
        return out.toString();
    }
}
