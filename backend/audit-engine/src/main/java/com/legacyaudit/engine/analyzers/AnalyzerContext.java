package com.legacyaudit.engine.analyzers;

import com.legacyaudit.model.config.AuditConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What an analyzer may read: the file, its text and the configuration.
 *
 * @param text the decoded text, or null for a binary or too large file
 * @param textRules the text rules in force; null means the built-in packs
 */
public record AnalyzerContext(AuditConfig config, Path absPath, String text, List<Object> textRules) {

    public byte[] readBytes() throws IOException {
        return Files.readAllBytes(absPath);
    }

    /** The text, or "" when there is none. */
    public String textOrEmpty() {
        return text == null ? "" : text;
    }
}
