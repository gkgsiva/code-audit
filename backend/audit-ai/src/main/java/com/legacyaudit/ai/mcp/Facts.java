package com.legacyaudit.ai.mcp;

import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.store.FactsQuery;
import com.legacyaudit.engine.store.FactsQuery.FactsError;
import com.legacyaudit.reports.RunData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only questions over one run, for the MCP server and {@code legacy-audit facts}: fact rows
 * ({@link FactsQuery}), one component with what it holds, one SQL statement, source lines around
 * an evidence location (credentials masked) and the scan's coverage. Nothing here writes.
 */
public final class Facts {

    public static final int MAX_LIMIT = FactsQuery.MAX_LIMIT;
    static final Pattern SECRET_LINE = Pattern.compile(
            "^(\\s*[\\w.-]*(?:passw(?:or)?d|pwd|passphrase|secret|api[_-]?key|token|credential)[\\w.-]*\\s*[:=]\\s*)(\\S.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Supplier<Path> runDir;

    /** {@code runDir} is asked on each call, so a re-scan is picked up. */
    public Facts(Supplier<Path> runDir) {
        this.runDir = runDir;
    }

    /** Rows of one kind; filter values match exactly, {@code ~text} means contains. */
    public Map<String, Object> queryFacts(String kind, Map<String, Object> filters, int limit, List<String> fields) {
        return new FactsQuery(runDir.get()).query(kind, filters, limit, 0, null, fields);
    }

    private Map<String, Object> one(String kind, String key, String value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(key, value);
        List<?> rows = (List<?>) queryFacts(kind, f, 1, null).get("rows");
        @SuppressWarnings("unchecked")
        Map<String, Object> row = rows.isEmpty() ? null : (Map<String, Object>) rows.get(0);
        return row;
    }

    /** One component (by id or name) with its services, screens, SQL, batch jobs and integrations. */
    public Map<String, Object> getComponent(String ref) {
        Map<String, Object> comp = one("components", "id", ref);
        if (comp == null) {
            comp = one("components", "name", ref);
        }
        if (comp == null) {
            throw new FactsError("No component with id or name '" + ref + "'");
        }
        Object name = comp.get("name");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("component", comp);
        Object[][] kinds = {
            {"services", List.of("id", "name", "kind", "complexity")},
            {"screens", List.of("id", "name", "partial", "complexity")},
            {"sql_statements", List.of("id", "type", "pg_grade", "file", "line")},
            {"batch_jobs", List.of("id", "name", "scheduler")},
        };
        for (Object[] k : kinds) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("component", name);
            @SuppressWarnings("unchecked")
            List<String> fields = (List<String>) k[1];
            out.put((String) k[0], queryFacts((String) k[0], f, 100, fields));
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("components", "~" + name);
        out.put("integrations", queryFacts("integrations", f, 100, List.of("id", "type", "target", "direction")));
        return out;
    }

    /** One SQL statement by id: text, how it was built, PostgreSQL grade. */
    public Map<String, Object> getSql(String sqlId) {
        Map<String, Object> row = one("sql_statements", "id", sqlId);
        if (row == null) {
            throw new FactsError("No SQL statement '" + sqlId + "'");
        }
        return row;
    }

    /** Source lines around a location (only files of the scanned source), credentials masked. */
    public Map<String, Object> readEvidence(String file, Integer line, int context) {
        RunData rd = RunData.load(runDir.get());
        Path path = rd.absPath(file);
        if (path == null || !Files.isRegularFile(path)) {
            throw new FactsError("'" + file + "' is not a file of the scanned source");
        }
        try {
            Path real = path.toRealPath();
            boolean inside = false;
            for (Path root : rd.roots.values()) {
                try {
                    if (real.startsWith(root.toRealPath())) {
                        inside = true;
                    }
                } catch (IOException e) {
                    // a root that is gone holds nothing
                }
            }
            if (!inside) {
                throw new FactsError("'" + file + "' is outside the scanned source");
            }
            List<String> lines = TextUtil.splitLines(new String(Files.readAllBytes(real), StandardCharsets.UTF_8));
            context = Math.max(0, Math.min(context, 200));
            int start = line == null ? 1 : Math.max(1, line - context);
            int end = Math.min(lines.size(), line != null ? line + context : 2 * context + 1);
            List<String> part = start - 1 < end ? lines.subList(start - 1, end) : List.of();
            Matcher m = SECRET_LINE.matcher(String.join("\n", part));
            StringBuilder text = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(text, Matcher.quoteReplacement(m.group(1) + "****"));
            }
            m.appendTail(text);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file", file);
            out.put("line", line);
            out.put("start", start);
            out.put("end", end);
            out.put("text", text.toString());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Input coverage, analyzer coverage and unresolved references of the scan. */
    public Map<String, Object> coverage() {
        try {
            Map<?, ?> run = JSON.readValue(Files.readString(runDir.get().resolve("run.json"), StandardCharsets.UTF_8), Map.class);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("run_id", run.get("run_id"));
            out.put("inputs", run.containsKey("coverage") ? run.get("coverage") : new ArrayList<>());
            out.put("analyzers", run.containsKey("analyzers") ? run.get("analyzers") : new LinkedHashMap<>());
            out.put("unresolved_references", run.containsKey("unresolved_references") ? run.get("unresolved_references") : new ArrayList<>());
            out.put("vulnerability_sources", run.containsKey("vulnerability_sources") ? run.get("vulnerability_sources") : new ArrayList<>());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
