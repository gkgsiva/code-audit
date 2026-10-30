package com.legacyaudit.engine.store;

import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** The figures of a run's first page: headline numbers, chart data, coverage, top findings and effort. */
public final class Dashboard {

    static final List<String> SEVERITY_ORDER = List.of("critical", "high", "medium", "low", "info");
    static final List<String> COMPLEXITY_ORDER = List.of("Simple", "Medium", "Complex", "Very complex");
    static final List<String> GRADE_ORDER = List.of("auto", "review", "manual");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Dashboard() {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path p) {
        try {
            return JSON.readValue(Files.readString(p, StandardCharsets.UTF_8), Map.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static long number(Object v) {
        return v instanceof Number n && !(v instanceof Double) ? n.longValue() : 0;
    }

    /** Categories in their natural order (severity, band, grade), unknown ones last. */
    static Map<String, Object> ordered(Map<String, Object> data, List<String> order) {
        List<String> keys = new ArrayList<>(data.keySet());
        keys.sort((a, b) -> {
            int ra = order.contains(a) ? order.indexOf(a) : order.size();
            int rb = order.contains(b) ? order.indexOf(b) : order.size();
            return ra != rb ? Integer.compare(ra, rb) : PyJson.compareCodePoints(a, b);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        keys.forEach(k -> out.put(k, data.get(k)));
        return out;
    }

    private static Map<String, Object> kpi(String label, Object value, String metric) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        m.put("metric", metric);
        return m;
    }

    public static Map<String, Object> of(Path runDir) {
        Map<String, Object> s = read(runDir.resolve("data").resolve("audit-summary.json"));
        Map<String, Object> run = read(runDir.resolve("run.json"));
        s = s == null ? Map.of() : s;
        run = run == null ? Map.of() : run;
        Map<String, Object> totals = map(s.get("totals"));
        Map<String, Object> counts = map(s.get("counts"));
        Map<String, Object> bands = new LinkedHashMap<>();
        for (Object perKind : map(counts.get("complexity")).values()) {
            map(perKind).forEach((band, n) -> bands.merge(band, number(n), (a, b) -> (Long) a + (Long) b));
        }
        List<Object> kpis = new ArrayList<>();
        kpis.add(kpi("Lines of code", totals.getOrDefault("loc_code", 0L), "totals.loc_code"));
        kpis.add(kpi("Components", totals.getOrDefault("components", 0L), "totals.components"));
        String[][] paths = {{"Services", "services.total"}, {"Screens", "screens.total"}, {"SQL statements", "sql.total"},
            {"Integrations", "integrations.total"}, {"Batch jobs", "batch_jobs.total"}, {"Vulnerable libraries", "vulnerable_libraries.total"}};
        for (String[] p : paths) {
            Object node = counts;
            for (String part : p[1].split("\\.")) {
                node = map(node).get(part);
            }
            kpis.add(kpi(p[0], number(node), "counts." + p[1]));
        }
        List<Map.Entry<String, Object>> langs = new ArrayList<>(map(s.get("by_language")).entrySet());
        langs.sort((a, b) -> Long.compare(number(map(b.getValue()).get("loc_code")), number(map(a.getValue()).get("loc_code"))));
        Map<String, Object> technology = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : langs) {
            long loc = number(map(e.getValue()).get("loc_code"));
            if (loc != 0) {
                technology.put(e.getKey(), loc);
            }
        }
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("technology", technology);
        charts.put("complexity", ordered(bands, COMPLEXITY_ORDER));
        charts.put("pg_grade", ordered(map(counts.get("pg_grades")), GRADE_ORDER));
        charts.put("severity", ordered(map(counts.get("findings_by_severity")), SEVERITY_ORDER));
        List<?> top = s.get("top_findings") instanceof List<?> l ? l : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runDir.getFileName().toString());
        out.put("application", run.get("application"));
        out.put("started_at", run.get("started_at"));
        out.put("kpis", kpis);
        out.put("charts", charts);
        out.put("coverage", s.getOrDefault("coverage", Map.of()));
        out.put("warnings", run.getOrDefault("warnings", List.of()));
        out.put("components", s.getOrDefault("components", List.of()));
        out.put("top_findings", top.subList(0, Math.min(10, top.size())));
        out.put("effort", s.get("effort"));
        out.put("timings", run.getOrDefault("timings", Map.of()));
        out.put("vulnerability_sources", s.getOrDefault("vulnerability_sources", List.of()));
        out.put("target", s.get("target"));
        return out;
    }
}
