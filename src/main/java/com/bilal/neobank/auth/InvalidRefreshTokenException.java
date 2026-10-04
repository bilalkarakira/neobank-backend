package com.bilal.neobank.auth;

/** Missing, unknown, expired, revoked or reused: deliberately indistinguishable to the caller. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("The refresh token is not valid");
    }
}
