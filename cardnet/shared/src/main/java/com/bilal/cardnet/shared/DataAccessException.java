package com.bilal.cardnet.shared;

/** A database operation failed. Unchecked, so request handlers can answer 96 instead of every layer declaring SQLException. */
public final class DataAccessException extends RuntimeException {

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
