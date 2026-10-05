package com.bilal.neobank.auth;

/**
 * Both are 422, never 401: a 401 on a signed-in endpoint means "your session expired" to the client and would log the
 * user out for simply mistyping their current password.
 */
public final class PasswordExceptions {

    private PasswordExceptions() {
    }

    public static class WrongCurrentPasswordException extends RuntimeException {
        public WrongCurrentPasswordException() {
            super("The current password is not correct");
        }
    }

    public static class SamePasswordException extends RuntimeException {
        public SamePasswordException() {
            super("The new password must be different from the current one");
        }
    }
}
