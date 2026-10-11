package com.bilal.cardnet.iso8583;

import java.nio.charset.StandardCharsets;

/**
 * Command line message inspector.
 *
 * <pre>
 *   java -cp iso8583.jar com.bilal.cardnet.iso8583.Inspect [--show-sensitive] [--hex] MESSAGE
 * </pre>
 * MESSAGE is the message text (as it travels on the wire, without the 2-byte length), or with {@code --hex} the same bytes
 * written as hexadecimal (with or without a leading 2-byte length header, which is removed when it matches).
 */
public final class Inspect {

    private Inspect() {
    }

    public static void main(String[] args) {
        boolean sensitive = false;
        boolean hex = false;
        String input = null;
        for (String arg : args) {
            switch (arg) {
                case "--show-sensitive" -> sensitive = true;
                case "--hex" -> hex = true;
                default -> input = arg;
            }
        }
        if (input == null) {
            System.err.println("usage: Inspect [--show-sensitive] [--hex] MESSAGE");
            System.exit(2);
        }
        try {
            byte[] bytes = hex ? fromHex(input) : input.getBytes(StandardCharsets.US_ASCII);
            if (hex && bytes.length > 2 && (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF)) == bytes.length - 2) {
                bytes = java.util.Arrays.copyOfRange(bytes, 2, bytes.length); // the TCP length header
            }
            Packager packager = new Packager();
            Message message = packager.unpack(bytes);
            System.out.print(Inspector.hexDump(bytes));
            System.out.println();
            System.out.print(Inspector.format(message, packager.spec(), sensitive));
        } catch (Iso8583Exception e) {
            System.err.println("Not a valid message: " + e.getMessage());
            System.exit(1);
        }
    }

    static byte[] fromHex(String text) {
        String clean = text.replaceAll("\\s+", "");
        if (clean.length() % 2 != 0 || !FieldType.B.accepts(clean.toUpperCase())) {
            throw new Iso8583Exception("--hex input must be an even number of hex characters");
        }
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}
