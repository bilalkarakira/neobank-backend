package com.bilal.cardnet.acquirer;

/** What a response code means, in the words a shopkeeper would want on the terminal. */
public final class PosTexts {

    private PosTexts() {
    }

    public static String reason(String code) {
        return switch (code) {
            case "00" -> "Approved.";
            case "05" -> "The bank did not approve this payment.";
            case "12" -> "The bank cannot add to this payment (closed, limit reached or too much).";
            case "13" -> "The payment request was not valid.";
            case "14" -> "The card details did not match.";
            case "15" -> "No bank is known for this card number.";
            case "N7" -> "The security code is wrong.";
            case "30" -> "The payment request was malformed.";
            case "51" -> "Not enough available money.";
            case "54" -> "The card has expired.";
            case "55" -> "Wrong PIN.";
            case "57" -> "The card is frozen, or this kind of payment is not allowed on it.";
            case "61" -> "Over the card's spending limit.";
            case "65" -> "Too many payments in a short time.";
            case "75" -> "Too many wrong PINs: the card was frozen.";
            case "91" -> "The bank could not be reached and the network could not approve it on its own.";
            case "94" -> "A repeated request with different details.";
            case "96" -> "The bank had a system problem.";
            default -> "The payment could not be processed.";
        };
    }
}
