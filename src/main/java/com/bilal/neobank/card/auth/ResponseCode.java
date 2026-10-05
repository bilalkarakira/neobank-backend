package com.bilal.neobank.card.auth;

/** ISO 8583 response codes (DE39) the issuer answers with, and what each means in plain words for the customer. */
public final class ResponseCode {

    public static final String APPROVED = "00";
    public static final String DO_NOT_HONOUR = "05";
    public static final String INVALID_AMOUNT = "13";
    public static final String INVALID_CARD = "14";
    public static final String FORMAT_ERROR = "30";
    public static final String INSUFFICIENT_FUNDS = "51";
    public static final String EXPIRED_CARD = "54";
    public static final String WRONG_PIN = "55";
    public static final String NOT_PERMITTED = "57";
    public static final String EXCEEDS_LIMIT = "61";
    public static final String VELOCITY = "65";
    public static final String PIN_TRIES_EXCEEDED = "75";
    public static final String ISSUER_UNAVAILABLE = "91";
    public static final String DUPLICATE = "94";
    public static final String SYSTEM_ERROR = "96";

    private ResponseCode() {
    }

    /** Null for an approval. */
    public static String reason(String code) {
        return switch (code) {
            case APPROVED -> null;
            case DO_NOT_HONOUR -> "The bank did not approve this payment.";
            case INVALID_AMOUNT -> "The amount was not valid.";
            case INVALID_CARD -> "The card details did not match.";
            case FORMAT_ERROR -> "The payment request was malformed.";
            case INSUFFICIENT_FUNDS -> "Not enough available money.";
            case EXPIRED_CARD -> "The card has expired.";
            case WRONG_PIN -> "Wrong PIN.";
            case NOT_PERMITTED -> "This kind of payment is not allowed on the card.";
            case EXCEEDS_LIMIT -> "Over the card's spending limit.";
            case VELOCITY -> "Too many payments in a short time.";
            case PIN_TRIES_EXCEEDED -> "Too many wrong PINs: the card was frozen.";
            case ISSUER_UNAVAILABLE -> "The bank could not take card payments at that moment.";
            case DUPLICATE -> "Repeated payment request with different details.";
            default -> "The payment could not be processed.";
        };
    }
}
