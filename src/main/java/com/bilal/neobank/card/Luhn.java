package com.bilal.neobank.card;

/** The Luhn (mod 10) check digit used by card numbers. */
public final class Luhn {

    private Luhn() {
    }

    /** The digit that makes {@code digits + digit} pass {@link #isValid}. */
    public static int checkDigit(String digits) {
        return (10 - sum(digits, true) % 10) % 10;
    }

    public static boolean isValid(String number) {
        if (number == null || number.length() < 2 || !number.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        return sum(number, false) % 10 == 0;
    }

    /**
     * Sum of the digits, doubling every second one counted from the right. When the check digit is not there yet, the
     * last digit given is the one next to it and is doubled.
     */
    private static int sum(String digits, boolean checkDigitMissing) {
        int sum = 0;
        boolean doubleIt = checkDigitMissing;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum;
    }
}
