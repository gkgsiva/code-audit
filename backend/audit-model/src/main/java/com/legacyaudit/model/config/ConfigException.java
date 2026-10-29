package com.legacyaudit.model.config;

/** An invalid or unreadable {@code audit.yaml} or {@code audit-overrides.yaml}. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }
}
