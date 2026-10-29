package com.legacyaudit.engine.analyzers;

import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The analyzers, in the order the Python tool registers them, and running them on one file. */
public final class Analyzers {

    private Analyzers() {}

    /** Every analyzer ported so far, in registration order. */
    public static List<Analyzer> registered() {
        return List.of(
                new BuildAnalyzer(),
                new DepsAnalyzer());
    }

    /** {@code inventory=<v>;<name>=<version>;...} by name: part of the file-cache key. */
    public static String versionKey(String inventoryVersion) {
        StringBuilder sb = new StringBuilder("inventory=" + inventoryVersion);
        registered().stream().sorted(Comparator.comparing(Analyzer::name))
                .forEach(a -> sb.append(';').append(a.name()).append('=').append(a.version()));
        return sb.toString();
    }

    /** Runs every analyzer that supports the file; a crash is a coverage gap and a finding. */
    public static Map<String, Facts> run(List<Analyzer> analyzers, FileRecord rec, AnalyzerContext ctx, List<String> warnings) {
        Map<String, Facts> out = new LinkedHashMap<>();
        for (Analyzer a : analyzers) {
            if (a.supports(rec)) {
                out.put(a.name(), runOne(a, rec, ctx, warnings));
            }
        }
        return out;
    }

    static Facts runOne(Analyzer a, FileRecord rec, AnalyzerContext ctx, List<String> warnings) {
        try {
            return a.analyze(rec, ctx);
        } catch (Exception | StackOverflowError err) {
            String type = err.getClass().getSimpleName();
            synchronized (warnings) {
                warnings.add("Analyzer " + a.name() + " failed on " + rec.path + ": " + type + ": " + err.getMessage());
            }
            Facts facts = new Facts();
            int lines = ctx.text() != null && !ctx.text().isEmpty() ? TextUtil.lineCount(ctx.text()) : 1;
            facts.coverage.lines = lines;
            facts.coverage.gap(1, lines, "analyzer error: " + type);
            facts.add("findings", Doc.of(Schemas.FINDING,
                    "id", Fact.makeId("finding", "analyzer-error", a.name(), rec.path),
                    "rule_id", "analyzer-error",
                    "severity", "low",
                    "category", "coverage",
                    "file", rec.path,
                    "message", "The " + a.name() + " analyzer could not read this file (" + type + "); it is reported as not parsed.",
                    "evidence", new ArrayList<>(List.of(Evidence.of(rec.path)))));
            return facts;
        }
    }
}
