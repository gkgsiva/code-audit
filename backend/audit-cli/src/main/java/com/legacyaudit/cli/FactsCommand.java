package com.legacyaudit.cli;

import com.legacyaudit.ai.mcp.Facts;
import com.legacyaudit.engine.store.FactsQuery.FactsError;
import com.legacyaudit.model.json.PyJson;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code legacy-audit facts}: facts of one kind as JSON (read-only; for Copilot and for people). */
@Command(name = "facts", description = "Print facts of one kind as JSON (read-only; for the Copilot agent and for people).")
final class FactsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "services, screens, sql_statements, ...")
    String kind;

    @Option(names = {"-f", "--filter"}, description = "field=value (repeatable); field=~text for contains.")
    List<String> filters;

    @Option(names = "--fields", description = "Comma-separated fields to show.")
    String fields;

    @Option(names = "--limit", defaultValue = "50", description = "At most this many rows (max 500).")
    int limit;

    @Mixin
    RunOptions opts;

    @Override
    public Integer call() {
        try {
            Path runDir = opts.runDir();
            Map<String, Object> f = new LinkedHashMap<>();
            for (String item : filters == null ? List.<String>of() : filters) {
                int eq = item.indexOf('=');
                if (eq < 0) {
                    throw new RunOptions.Failure("--filter needs field=value, got '" + item + "'");
                }
                f.put(item.substring(0, eq).strip(), item.substring(eq + 1));
            }
            List<String> cols = fields == null ? null : List.of(fields.split(",")).stream().map(String::strip).toList();
            Map<String, Object> result = new Facts(() -> runDir).queryFacts(kind, f, limit, cols);
            System.out.print(PyJson.pretty(result, false));
            return 0;
        } catch (RunOptions.Failure | FactsError e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
