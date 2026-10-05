package com.bilal.cardnet.iso8583;

/** Helpers for the four-digit message type indicator: version, class, function, origin. */
public final class Mti {

    private Mti() {
    }

    /** True for what a sender initiates (request 0, advice 2, notification 4); false for answers (1, 3, 5). */
    public static boolean isRequest(String mti) {
        char function = function(mti);
        return function == '0' || function == '2' || function == '4';
    }

    /**
     * The answer MTI: 0100 -> 0110, 0420 -> 0430, 1240 -> 1250 (a notification acknowledgement). In practice a clearing
     * presentment (1240) is not answered message by message: the file as a whole is acknowledged.
     */
    public static String responseFor(String mti) {
        if (!isRequest(mti)) {
            throw new Iso8583Exception(mti + " is already a response");
        }
        char function = function(mti);
        return mti.substring(0, 2) + (char) (function + 1) + mti.charAt(3);
    }

    /** "ISO 8583:1987, Authorization, Request, Acquirer". */
    public static String describe(String mti) {
        function(mti); // validates
        String version = switch (mti.charAt(0)) {
            case '0' -> "ISO 8583:1987";
            case '1' -> "ISO 8583:1993";
            case '2' -> "ISO 8583:2003";
            default -> "version " + mti.charAt(0);
        };
        String messageClass = switch (mti.charAt(1)) {
            case '1' -> "Authorization";
            case '2' -> "Financial";
            case '3' -> "File action";
            case '4' -> "Reversal";
            case '5' -> "Reconciliation";
            case '6' -> "Administrative";
            case '7' -> "Fee collection";
            case '8' -> "Network management";
            default -> "class " + mti.charAt(1);
        };
        String function = switch (mti.charAt(2)) {
            case '0' -> "Request";
            case '1' -> "Request response";
            case '2' -> "Advice";
            case '3' -> "Advice response";
            case '4' -> "Notification";
            case '5' -> "Notification acknowledgement";
            default -> "function " + mti.charAt(2);
        };
        String origin = switch (mti.charAt(3)) {
            case '0' -> "Acquirer";
            case '1' -> "Acquirer repeat";
            case '2' -> "Card issuer";
            case '3' -> "Card issuer repeat";
            default -> "origin " + mti.charAt(3);
        };
        return version + ", " + messageClass + ", " + function + ", " + origin;
    }

    private static char function(String mti) {
        if (mti == null || mti.length() != 4 || !FieldType.N.accepts(mti)) {
            throw new Iso8583Exception("the MTI must be 4 digits, not '" + mti + "'");
        }
        return mti.charAt(2);
    }
}
