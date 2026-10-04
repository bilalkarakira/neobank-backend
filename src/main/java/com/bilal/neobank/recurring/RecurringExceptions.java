package com.bilal.neobank.recurring;

import java.util.Map;

/** Business errors of the recurring-payments feature; mapped to problem+json in GlobalExceptionHandler. */
public final class RecurringExceptions {

    private RecurringExceptions() {
    }

    /** Unknown id, someone else's order, or a cancelled one: all look the same. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException() {
            super("Recurring payment not found");
        }
    }

    public static class LimitReachedException extends RuntimeException {
        public LimitReachedException(int max) {
            super("You can have at most %d recurring payments.".formatted(max));
        }
    }

    public static class InvalidStateException extends RuntimeException {
        public InvalidStateException(String message) {
            super(message);
        }
    }

    public static class InvalidScheduleException extends RuntimeException {
        private final Map<String, String> fields;

        public InvalidScheduleException(String field, String message) {
            super(message);
            this.fields = Map.of(field, message);
        }

        public Map<String, String> getFields() {
            return fields;
        }
    }
}
