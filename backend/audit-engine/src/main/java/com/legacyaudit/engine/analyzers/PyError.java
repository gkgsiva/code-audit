package com.legacyaudit.engine.analyzers;

/** An error the Python tool raises with a given type name (ValueError, KeyError, ...). */
public class PyError extends RuntimeException {

    private final String type;

    public PyError(String type, String message) {
        super(message);
        this.type = type;
    }

    /** The Python exception type name, as analyzer-error findings report it. */
    public String type() {
        return type;
    }
}
