package com.bilal.neobank.history;

import java.util.Map;

/** One or more query parameters of a history request are invalid (field name to message). */
public class InvalidFilterException extends RuntimeException {

    private final Map<String, String> fields;

    public InvalidFilterException(Map<String, String> fields) {
        super("Invalid history filter: " + fields);
        this.fields = fields;
    }

    public Map<String, String> getFields() {
        return fields;
    }
}
