package com.bilal.neobank.card;

/** Business errors of the cards feature; mapped to problem+json in GlobalExceptionHandler. */
public final class CardExceptions {

    private CardExceptions() {
    }

    /** Unknown id, someone else's card, or a cancelled one: all look the same on purpose. */
    public static class CardNotFoundException extends RuntimeException {
        public CardNotFoundException() {
            super("Card not found");
        }
    }

    public static class CardLimitReachedException extends RuntimeException {
        public CardLimitReachedException(int max) {
            super("You can have at most %d cards. Cancel one to get a new one.".formatted(max));
        }
    }

    /** Cards can only be linked to the customer's own ACTIVE checking account. */
    public static class AccountNotEligibleException extends RuntimeException {
        public AccountNotEligibleException() {
            super("A card can only be linked to one of your active checking accounts.");
        }
    }

    public static class InvalidStateException extends RuntimeException {
        public InvalidStateException(String message) {
            super(message);
        }
    }

    public static class InvalidLimitException extends RuntimeException {
        public InvalidLimitException(String message) {
            super(message);
        }
    }
}
