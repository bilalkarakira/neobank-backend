package com.bilal.cardnet.iso8583;

import java.util.Optional;

/**
 * ISO 9564 PIN block, format 0, written as 16 hex characters (what DE 52 carries). <b>Simulated: the block is NOT
 * encrypted.</b> A real terminal encrypts it under a PIN key and the issuer verifies it inside an HSM; here the XOR with
 * the PAN is the only protection, which is none. Encryption with test keys is a later phase.
 *
 * <pre>
 * PIN field : 0 L P P P P (P..) F F F ...   L = PIN length (4-12), padded with F to 16 hex digits
 * PAN field : 0 0 0 0 + the 12 rightmost digits of the PAN without its check digit
 * block     : PIN field XOR PAN field
 * </pre>
 */
public final class PinBlock {

    private PinBlock() {
    }

    public static String encode(String pin, String pan) {
        if (pin == null || !pin.matches("\\d{4,12}")) {
            throw new Iso8583Exception("a PIN is 4 to 12 digits");
        }
        String pinField = ("0" + Integer.toHexString(pin.length()) + pin + "FFFFFFFFFFFFFFFF").substring(0, 16)
                .toUpperCase();
        return xor(pinField, panField(pan));
    }

    /** The PIN, or empty when the block is not a well-formed format 0 block for this PAN. */
    public static Optional<String> decode(String block, String pan) {
        if (block == null || !block.matches("[0-9A-Fa-f]{16}")) {
            return Optional.empty();
        }
        String pinField = xor(block.toUpperCase(), panField(pan));
        if (pinField.charAt(0) != '0') {
            return Optional.empty();
        }
        int length = Character.digit(pinField.charAt(1), 16);
        if (length < 4 || length > 12) {
            return Optional.empty();
        }
        String digits = pinField.substring(2, 2 + length);
        String padding = pinField.substring(2 + length);
        if (!digits.matches("\\d+") || !padding.matches("F*")) {
            return Optional.empty();
        }
        return Optional.of(digits);
    }

    private static String panField(String pan) {
        if (pan == null || !pan.matches("\\d{13,19}")) {
            throw new Iso8583Exception("the PAN must be 13 to 19 digits");
        }
        String withoutCheckDigit = pan.substring(0, pan.length() - 1);
        return "0000" + withoutCheckDigit.substring(withoutCheckDigit.length() - 12);
    }

    private static String xor(String a, String b) {
        StringBuilder out = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            out.append(Integer.toHexString(Character.digit(a.charAt(i), 16) ^ Character.digit(b.charAt(i), 16)).toUpperCase());
        }
        return out.toString();
    }
}
