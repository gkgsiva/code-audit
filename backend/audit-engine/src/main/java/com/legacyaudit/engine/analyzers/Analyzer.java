package com.legacyaudit.engine.analyzers;

import com.legacyaudit.model.facts.FileRecord;

/**
 * Looks at one file and returns facts. It never looks at other files, so its result can be
 * cached per file; cross-file resolution is the linker's job (symbols and refs). Results are
 * cached under every analyzer's name and version, so a new version re-analyses the files.
 */
public interface Analyzer {

    String name();

    String version();

    boolean supports(FileRecord file);

    Facts analyze(FileRecord file, AnalyzerContext ctx) throws Exception;
}
