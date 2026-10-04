package com.bilal.neobank.account;

/**
 * Builds IBANs of the form XX{check}NEOB{10-digit account number}.
 * "XX" is reserved by ISO 3166 for user-assigned use, so it can never collide with a real IBAN.
 * The two check digits follow ISO 13616 (mod-97).
 */
public final class IbanGenerator {

    private static final String COUNTRY = "XX";
    private static final String BANK_CODE = "NEOB";

    private IbanGenerator() {
    }

    public static String generate(long accountNumber) {
        String bban = BANK_CODE + String.format("%010d", accountNumber);
        int check = 98 - mod97(bban + COUNTRY + "00");
        return COUNTRY + String.format("%02d", check) + bban;
    }

    /** Users paste IBANs with spaces and in any case: "xx65 neob 1000 0000 01" -> "XX65NEOB1000000001". */
    public static String normalize(String iban) {
        return iban == null ? "" : iban.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
    }

    /** ISO 13616 validation: move the first four characters to the end, and the number must be 1 mod 97. */
    public static boolean isValid(String iban) {
        if (iban == null || iban.length() < 5 || !iban.matches("[A-Z0-9]+")) {
            return false;
        }
        return mod97(iban.substring(4) + iban.substring(0, 4)) == 1;
    }

    /** Letters count as numbers (A=10 ... Z=35); the remainder is computed digit by digit to avoid huge integers. */
    private static int mod97(String text) {
        int remainder = 0;
        for (char c : text.toCharArray()) {
            int value = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            remainder = (value < 10 ? remainder * 10 + value : remainder * 100 + value) % 97;
        }
        return remainder;
    }
}
