package com.legacyaudit.ai.model;

/** A model could not be called or answered with an error; the message is safe to print. */
public class AiError extends RuntimeException {
    public AiError(String message) {
        super(message);
    }
}
