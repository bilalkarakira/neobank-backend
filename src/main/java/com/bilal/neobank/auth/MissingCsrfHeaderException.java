package com.bilal.neobank.auth;

/** The cookie-authenticated auth endpoints require a custom header that a cross-site form or image cannot send. */
public class MissingCsrfHeaderException extends RuntimeException {

    public static final String HEADER = "X-Requested-With";
    public static final String VALUE = "neobank";

    public MissingCsrfHeaderException() {
        super("The %s: %s header is required".formatted(HEADER, VALUE));
    }
}
