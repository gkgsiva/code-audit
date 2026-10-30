package com.legacyaudit.cli;

import com.legacyaudit.engine.store.Diff;
import com.legacyaudit.engine.store.FactWriter;
import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code legacy-audit diff}: compare two runs, counts and added / removed / changed records per entity. */
@Command(name = "diff", description = "Compare two runs: counts and added / removed / changed records per entity.")
final class DiffCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Run id, 'previous', 'latest' or folder.")
    String runA;

    @Parameters(index = "1", defaultValue = "latest", description = "Run id, 'previous', 'latest' or folder (default: latest).")
    String runB;

    @Option(names = {"-c", "--config"}, description = "Path to audit.yaml (default: audit.yaml).")
    Path config = Path.of("audit.yaml");

    @Option(names = {"-o", "--output-dir"}, description = "Override output_dir.")
    Path outputDir;

    @Option(names = "--json", description = "Write the full diff as JSON here.")
    Path jsonOut;

    @Option(names = "--show", defaultValue = "10", description = "Records listed per entity (default: 10).")
    int show;

    @Override
    @SuppressWarnings("unchecked")
    public Integer call() throws Exception {
        Path out = outputDir;
        Path a;
        Path b;
        try {
            if (out == null) {
                out = ConfigLoader.load(config).outputDir();
            }
            a = Runs.resolve(out, runA);
            b = Runs.resolve(out, runB);
        } catch (ConfigException | Runs.RunNotFound e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        Map<String, Object> result = Diff.runs(a, b);
        System.out.println(result.get("run_a") + "  ->  " + result.get("run_b"));
        System.out.println(String.format("%-16s %8s %8s %8s %8s %8s", "entity", "before", "after", "added", "removed", "changed"));
        Map<String, Object> entities = (Map<String, Object>) result.get("entities");
        entities.forEach((entity, v) -> {
            Map<String, Object> d = (Map<String, Object>) v;
            if ((Long) d.get("count_a") > 0 || (Long) d.get("count_b") > 0) {
                System.out.println(String.format("%-16s %8d %8d %8d %8d %8d", entity, d.get("count_a"), d.get("count_b"),
                        ((List<?>) d.get("added")).size(), ((List<?>) d.get("removed")).size(), ((List<?>) d.get("changed")).size()));
            }
        });
        entities.forEach((entity, v) -> {
            Map<String, Object> d = (Map<String, Object>) v;
            List<String> lines = new ArrayList<>();
            ((List<Map<String, Object>>) d.get("added")).forEach(r -> lines.add("  + " + r.get("label")));
            ((List<Map<String, Object>>) d.get("removed")).forEach(r -> lines.add("  - " + r.get("label")));
            for (Map<String, Object> c : (List<Map<String, Object>>) d.get("changed")) {
                List<String> fields = new ArrayList<>();
                ((Map<String, List<Object>>) c.get("fields")).forEach((k, pair) -> {
                    if (!(pair.get(0) instanceof List<?> || pair.get(0) instanceof Map<?, ?>)) {
                        fields.add(k + ": " + pair.get(0) + " -> " + pair.get(1));
                    }
                });
                lines.add("  ~ " + c.get("label") + (fields.isEmpty() ? "" : " (" + String.join(", ", fields) + ")"));
            }
            if (!lines.isEmpty()) {
                System.out.println(entity);
                lines.subList(0, Math.min(show, lines.size())).forEach(System.out::println);
                if (lines.size() > show) {
                    System.out.println("  ... " + (lines.size() - show) + " more (see --json)");
                }
            }
        });
        if (jsonOut != null) {
            FactWriter.writeJson(jsonOut, result);
            System.out.println("Full diff written to " + jsonOut);
        }
        return 0;
    }
}
