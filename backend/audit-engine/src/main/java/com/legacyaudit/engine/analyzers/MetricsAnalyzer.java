package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.metrics.Tokens;
import com.legacyaudit.engine.metrics.Units;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.FileRecord;
import java.util.List;
import java.util.Set;

/**
 * The {@code metrics} analyzer: code units with cyclomatic complexity, and duplication windows.
 *
 * <p>It runs per file like the other analyzers, so its results are cached; the cross-file parts
 * (clone matching, bands, dead code) are computed every run by the metrics step of the scan.
 */
public final class MetricsAnalyzer implements Analyzer {

    static final Set<String> TREE = Set.of("java", "c", "cpp", "proc");
    static final Set<String> SCRIPT_TREE = Set.of("typescript", "javascript");
    static final Set<String> SCRIPTS = Set.of("shell", "perl", "python", "batch");
    static final Set<String> SQL = Set.of("sql", "plsql");
    static final Set<String> BASIC = Set.of("vb6", "vbnet");
    static final Set<String> DUPLICATION = Set.of("java", "c", "cpp", "proc", "shell", "perl", "python", "batch", "sql", "plsql", "vb6",
            "vbnet", "cobol", "procobol", "jsp", "javascript", "typescript", "csharp");

    @Override
    public String name() {
        return "metrics";
    }

    @Override
    public String version() {
        return "3"; // 2: TypeScript/JavaScript units; 3: C#, VB6 and VB.NET
    }

    @Override
    public boolean supports(FileRecord file) {
        return DUPLICATION.contains(file.language) && file.category != Category.GENERATED && file.category != Category.BINARY;
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        String lang = file.language;
        String code;
        if (lang.equals("cobol") || lang.equals("procobol")) {
            List<String> lines = CobolAnalyzer.sourceLines(text);
            code = String.join("\n", lines);
            facts.units.addAll(Units.cobolUnits(lines));
        } else if (BASIC.contains(lang)) {
            code = BasicAnalyzer.stripCommentsBasic(text);
            facts.units.addAll(Units.vbUnits(text));
        } else {
            code = Tokens.stripComments(text, lang.equals("proc") ? "c" : lang);
            if (lang.equals("proc")) {
                Embedded.Result sql = Embedded.extract(code, file.path, null, false, null);
                code = Embedded.blankSpans(code, sql.spans);
            }
            if (TREE.contains(lang)) {
                facts.units.addAll(Units.treeUnits(code, lang));
            } else if (SCRIPT_TREE.contains(lang) && !file.path.contains("node_modules/")) {
                facts.units.addAll(Units.tsUnits(text, file.path)); // tree-sitter skips comments
            } else if (lang.equals("csharp")) {
                facts.units.addAll(Units.csUnits(text)); // tree-sitter skips comments
            } else if (SQL.contains(lang)) {
                facts.units.addAll(Units.plsqlUnits(code));
            } else if (SCRIPTS.contains(lang)) {
                facts.units.addAll(Units.scriptUnit(code, lang, TextUtil.afterLast(file.path, "/")));
            }
        }
        facts.windows.addAll(Tokens.windows(code, ctx.config().duplicationTokens()));
        facts.coverage.lines = text.isEmpty() ? 0 : TextUtil.count(text, '\n') + 1;
        return facts;
    }
}
