package com.bilal.neobank.admin;

/** Business errors of the admin feature; mapped to problem+json in GlobalExceptionHandler. */
public final class AdminExceptions {

    private AdminExceptions() {
    }

    /** For example: freezing an account that is already frozen or closed. */
    public static class InvalidStateException extends RuntimeException {
        public InvalidStateException(String message) {
            super(message);
        }
    }
}
