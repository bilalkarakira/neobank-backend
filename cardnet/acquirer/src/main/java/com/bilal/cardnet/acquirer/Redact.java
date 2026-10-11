package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.iso8583.Inspector;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;

import java.nio.charset.StandardCharsets;

/**
 * What the POS console may show of a message. The decoded form comes from the Inspector (card number masked to first six and
 * last four, PIN data and the security code hidden). The hex dump is of the real bytes, which carry the number and the PIN block as text, so those
 * bytes are overwritten with asterisks first: the dump keeps its shape and its length, and reveals nothing.
 */
public final class Redact {

    private static final Packager PACKAGER = new Packager();

    private Redact() {
    }

    public static String decoded(Message message) {
        return Inspector.format(message);
    }

    public static String hexDump(Message message) {
        return Inspector.hexDump(redactedBytes(message));
    }

    /** The packed message with the middle of the card number and all of the PIN block overwritten; same length as the real one. */
    static byte[] redactedBytes(Message message) {
        byte[] bytes = PACKAGER.pack(message);
        if (message.has(2)) {
            hide(bytes, message.get(2), 6, message.get(2).length() - 4);
        }
        if (message.has(52)) {
            hide(bytes, message.get(52), 0, message.get(52).length());
        }
        if (message.has(53)) {
            hidePosition(bytes, message, 53);
        }
        return bytes;
    }

    /**
     * Overwrites the value of one element by its place in the packed message. A short secret (a three digit security code) cannot be
     * found by its value: the same digits may be part of the amount or the trace number. The place is found by packing the message with
     * two different values of the same length and looking at which bytes differ.
     */
    private static void hidePosition(byte[] bytes, Message message, int element) {
        String value = message.get(element);
        byte[] zeros = PACKAGER.pack(message.copyWithMti(message.mti()).set(element, "0".repeat(value.length())));
        byte[] nines = PACKAGER.pack(message.copyWithMti(message.mti()).set(element, "9".repeat(value.length())));
        for (int i = 0; i < bytes.length && i < zeros.length; i++) {
            if (zeros[i] != nines[i]) {
                bytes[i] = '*';
            }
        }
    }

    /** Overwrites characters [from, to) of every occurrence of {@code secret} in the bytes with '*'. */
    private static void hide(byte[] bytes, String secret, int from, int to) {
        byte[] needle = secret.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + needle.length <= bytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length && match; j++) {
                match = bytes[i + j] == needle[j];
            }
            if (match) {
                for (int j = from; j < to; j++) {
                    bytes[i + j] = '*';
                }
            }
        }
    }
}
