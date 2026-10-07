package com.bilal.neobank.card.dispute;

/** Business errors of card disputes; mapped to problem+json in GlobalExceptionHandler. */
public final class DisputeExceptions {

    private DisputeExceptions() {
    }

    /** Unknown payment or someone else's: both look the same on purpose. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException() {
            super("Payment not found");
        }
    }

    /** The payment exists but cannot be disputed (too old, already disputed, bad amount, account not active ...). */
    public static class NotAllowedException extends RuntimeException {
        public NotAllowedException(String message) {
            super(message);
        }
    }
}
