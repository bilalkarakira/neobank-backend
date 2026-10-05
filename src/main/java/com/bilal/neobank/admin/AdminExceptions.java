package com.bilal.neobank.admin;

/** Business errors of the admin feature; mapped to problem+json in GlobalExceptionHandler. */
public final class AdminExceptions {

    private AdminExceptions() {
    }

    /** Unknown id, or not a customer (administrators are not browsable here). */
    public static class CustomerNotFoundException extends RuntimeException {
        public CustomerNotFoundException() {
            super("Customer not found");
        }
    }

    /** An account can only be closed when it holds nothing: the money must be moved out first. */
    public static class AccountNotEmptyException extends RuntimeException {
        public AccountNotEmptyException(String balance) {
            super("The account still holds %s. It can only be closed when the balance is exactly 0.00.".formatted(balance));
        }
    }

    /** Money is reserved by card authorizations: closing would strand them. */
    public static class AccountHasHoldsException extends RuntimeException {
        public AccountHasHoldsException() {
            super("The account has card payments waiting to be cleared. It can be closed once they are reversed or expired.");
        }
    }

    /** There is no active login lock for that email (never locked, already expired, or already unlocked). */
    public static class NotLockedException extends RuntimeException {
        public NotLockedException() {
            super("That email is not locked out.");
        }
    }

    /** The user is not blocked for probing right now. */
    public static class NotBlockedException extends RuntimeException {
        public NotBlockedException() {
            super("That user is not blocked.");
        }
    }

    /** For example: freezing an account that is already frozen or closed. */
    public static class InvalidStateException extends RuntimeException {
        public InvalidStateException(String message) {
            super(message);
        }
    }
}
