package com.bilal.cardnet.shared;

/** What may be shown or stored of a card number: the first six and last four digits, nothing else. */
public final class Pans {

    private Pans() {
    }

    /** "9999000000000012" -> "999900******0012"; anything too short to mask safely is fully hidden. */
    public static String mask(String pan) {
        if (pan == null || pan.length() < 13) {
            return "*".repeat(pan == null ? 0 : pan.length());
        }
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }

    public static String last4(String pan) {
        return pan == null || pan.length() < 4 ? "" : pan.substring(pan.length() - 4);
    }

    /** The Luhn (mod 10) check of a full card number. */
    public static boolean luhnValid(String pan) {
        if (pan == null || pan.length() < 13 || !pan.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int d = pan.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
