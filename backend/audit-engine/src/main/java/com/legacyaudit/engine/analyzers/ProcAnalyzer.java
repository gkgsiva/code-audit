package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;

import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.FileRecord;
import java.util.List;

/**
 * Pro*C ({@code .pc}): the {@code EXEC SQL} blocks are blanked and the C is handed to the C
 * analyzer, then the blocks become {@code SqlStatement}s (source {@code embedded});
 * {@code PREPARE ... FROM :buffer} is rebuilt from the C that fills the buffer. Pro*COBOL is
 * handled by the COBOL analyzer, which reads {@code EXEC SQL ... END-EXEC} itself.
 */
public final class ProcAnalyzer implements Analyzer {

    @Override
    public String name() {
        return "proc";
    }

    @Override
    public String version() {
        return "3";
    }

    @Override
    public boolean supports(FileRecord file) {
        return file.language.equals("proc");
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        String path = file.path;
        facts.coverage.lines = TextUtil.count(text, '\n') + 1;
        // The C part first, so PREPARE ... FROM :buffer can be rebuilt from the C that fills it.
        CSql csql = CAnalyzer.analyzeC(path, Embedded.blankSpans(text, Embedded.blockSpans(text, false)), facts, false);
        String dialect = ctx.config().sqlDialect();
        Embedded.Result res = Embedded.extract(text, path, dialect, false, csql::valueAt);
        if (!res.spans.isEmpty()) {
            facts.add("tech_stack", tech("Oracle Pro*C", null, "language", path, 1));
        }
        for (Doc stmt : res.statements) {
            facts.add("sql_statements", stmt);
        }
        for (Embedded.Include inc : res.includes) {
            facts.ref("includes", "file:" + path, path, inc.member(), List.of("file-name"), path, inc.line());
        }
        for (Embedded.Span s : res.unparsed) {
            facts.coverage.gap(s.start(), s.end(), "embedded SQL not parsed");
        }
        CAnalyzer.emitCSql(path, csql, facts, dialect);
        return facts;
    }
}
