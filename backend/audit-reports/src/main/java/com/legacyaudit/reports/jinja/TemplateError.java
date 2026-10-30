package com.legacyaudit.reports.jinja;

/** A template that cannot be parsed or rendered; the message names the template and line when known. */
public final class TemplateError extends RuntimeException {

    public TemplateError(String message) {
        super(message);
    }
}
