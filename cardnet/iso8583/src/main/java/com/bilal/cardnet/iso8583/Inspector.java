package com.bilal.cardnet.iso8583;

import java.util.List;
import java.util.Map;

/**
 * Human-readable views of a message and its bytes. Sensitive elements (PAN, PIN data) are masked unless the caller asks
 * for them: these printouts end up in consoles, logs and bug reports.
 */
public final class Inspector {

    private Inspector() {
    }

    /** One line: the MTI and the element numbers present. Never shows a value. */
    public static String summary(Message message) {
        return "Message[" + message.mti() + " DE " + message.fields().keySet() + "]";
    }

    /** At most the first six and last four digits, the rest as asterisks (the usual display rule for a PAN). */
    public static String maskPan(String pan) {
        if (pan.length() <= 10) {
            return "*".repeat(pan.length());
        }
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }

    /** A field-by-field printout of the message. */
    public static String format(Message message, Spec spec, boolean showSensitive) {
        StringBuilder out = new StringBuilder();
        out.append("MTI ").append(message.mti()).append("  (").append(Mti.describe(message.mti())).append(")\n");
        Map<Integer, String> fields = message.fields();
        out.append("Data elements present: ").append(fields.keySet()).append('\n');
        for (Map.Entry<Integer, String> entry : fields.entrySet()) {
            int number = entry.getKey();
            FieldDef def = spec.get(number).orElse(null);
            String name = def == null ? "(not defined)" : def.name();
            String value = entry.getValue();
            boolean masked = def != null && def.sensitive() && !showSensitive;
            String shown = masked ? (number == 2 ? maskPan(value) : "*".repeat(value.length())) : value;
            // always "\n", whatever the platform: the samples and the tests compare this text byte for byte
            out.append(String.format("  DE %3d  %-36s %s\n", number, name, shown));
            if (number == 55 && !masked) {
                try {
                    appendTlv(out, Tlv.parse(value), 6);
                } catch (Iso8583Exception e) {
                    out.append("          (chip data is not valid TLV: ").append(e.getMessage()).append(")\n");
                }
            }
        }
        return out.toString();
    }

    public static String format(Message message) {
        return format(message, Spec.ISO87, false);
    }

    private static void appendTlv(StringBuilder out, List<Tlv.Entry> entries, int indent) {
        for (Tlv.Entry entry : entries) {
            out.append(" ".repeat(indent + 4)).append(String.format("%-6s", entry.tag()));
            String name = Tlv.NAMES.get(entry.tag());
            if (name != null) {
                out.append(String.format(" %-34s", name));
            }
            out.append(entry.constructed() ? "" : " " + entry.valueHex()).append('\n');
            if (entry.constructed()) {
                appendTlv(out, entry.children(), indent + 4);
            }
        }
    }

    /** A classic hex dump: offset, 16 bytes in hex, and the printable characters. */
    public static String hexDump(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < bytes.length; offset += 16) {
            out.append(String.format("%04X  ", offset));
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (offset + i < bytes.length) {
                    int b = bytes[offset + i] & 0xFF;
                    out.append(String.format("%02X ", b));
                    text.append(b >= 0x20 && b <= 0x7E ? (char) b : '.');
                } else {
                    out.append("   ");
                }
                if (i == 7) {
                    out.append(' ');
                }
            }
            out.append(" |").append(text).append("|\n");
        }
        return out.toString();
    }
}
