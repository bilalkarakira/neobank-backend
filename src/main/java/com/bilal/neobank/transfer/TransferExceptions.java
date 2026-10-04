package com.bilal.neobank.transfer;

import java.math.BigDecimal;

/** Business errors of the transfer feature; each maps to a problem+json response in GlobalExceptionHandler. */
public final class TransferExceptions {

    private TransferExceptions() {
    }

    /** The IBAN fails the ISO mod-97 check, so it is certainly mistyped. */
    public static class InvalidIbanException extends RuntimeException {
        public InvalidIbanException() {
            super("The IBAN is not valid");
        }
    }

    public static class RecipientNotFoundException extends RuntimeException {
        public RecipientNotFoundException() {
            super("No account has this IBAN");
        }
    }

    public static class RecipientUnavailableException extends RuntimeException {
        public RecipientUnavailableException() {
            super("The recipient account cannot receive money right now");
        }
    }

    public static class SameAccountException extends RuntimeException {
        public SameAccountException() {
            super("The source and destination accounts must be different");
        }
    }

    public static class DailyLimitExceededException extends RuntimeException {
        private final BigDecimal limit;
        private final BigDecimal remaining;

        public DailyLimitExceededException(BigDecimal limit, BigDecimal remaining) {
            super("Daily transfer limit of %s exceeded. Remaining today: %s."
                    .formatted(limit.toPlainString(), remaining.toPlainString()));
            this.limit = limit;
            this.remaining = remaining;
        }

        public BigDecimal getLimit() { return limit; }
        public BigDecimal getRemaining() { return remaining; }
    }
}
