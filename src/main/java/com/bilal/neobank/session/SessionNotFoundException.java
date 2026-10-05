package com.bilal.neobank.session;

/** Unknown id or someone else's session: indistinguishable on purpose. */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException() {
        super("Session not found");
    }
}
