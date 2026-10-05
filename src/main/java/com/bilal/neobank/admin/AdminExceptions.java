package com.bilal.neobank.admin;

/** Business errors of the admin feature; mapped to problem+json in GlobalExceptionHandler. */
public final class AdminExceptions {

    private AdminExceptions() {
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
