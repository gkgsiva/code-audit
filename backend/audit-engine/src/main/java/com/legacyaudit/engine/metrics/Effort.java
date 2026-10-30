package com.legacyaudit.engine.metrics;

import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.model.json.PyNum;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Effort model: person-days [low, likely, high] from the weights in {@code effort.weights}.
 *
 * <p>Every item counted elsewhere is priced by its level: services, screens (not partials) and
 * batch jobs by complexity band; distinct SQL statements by PostgreSQL grade; stored code by
 * grade; integrations each; libraries by recommended action. The result is labelled
 * "estimate — weights from config": it is arithmetic on counts, not a forecast.
 */
public final class Effort {

    static final String DATABASE = "(database)";
    static final String SHARED = "(shared)";
    private static final List<String> MANUAL_OBJECTS = List.of("package", "procedure", "function", "trigger");

    /** The built-in weights; {@code effort.weights} in audit.yaml adds or replaces levels. */
    static final Map<String, Map<String, List<Number>>> DEFAULTS = new LinkedHashMap<>();

    static {
        put("service", "simple", 1, 2, 3, "medium", 2, 4, 6, "complex", 5, 8, 12, "very_complex", 10, 15, 25);
        put("screen", "simple", 0.5, 1, 2, "medium", 1, 2, 3, "complex", 2, 4, 6, "very_complex", 4, 7, 10);
        put("batch_job", "simple", 1, 2, 3, "medium", 2, 3, 5, "complex", 4, 6, 9, "very_complex", 8, 12, 18);
        put("sql", "auto", 0, 0.05, 0.1, "review", 0.25, 0.5, 1, "manual", 1, 2, 3);
        put("stored_procedure", "review", 1, 2, 4, "manual", 2, 4, 8);
        put("integration", "each", 2, 4, 8);
        put("library", "keep", 0, 0, 0, "use-bom", 0, 0.1, 0.25, "upgrade", 0.25, 0.5, 1, "migrate-jakarta", 0.5, 1, 2, "remove", 0.5, 1, 2,
                "replace", 3, 5, 10, "rewrite", 10, 20, 40);
    }

    private static void put(String item, Object... levels) {
        Map<String, List<Number>> m = new LinkedHashMap<>();
        for (int i = 0; i < levels.length; i += 4) {
            List<Number> triple = new ArrayList<>();
            for (int k = 1; k <= 3; k++) {
                Object v = levels[i + k];
                triple.add(v instanceof Integer n ? (Number) n.longValue() : (Number) v);
            }
            m.put((String) levels[i], triple);
        }
        DEFAULTS.put(item, m);
    }

    private Effort() {}

    /** The defaults with the configured weights merged in (configured values as floats, as Python validated them). */
    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, List<Number>>> weights(Object configured) {
        Map<String, Map<String, List<Number>>> merged = new LinkedHashMap<>();
        DEFAULTS.forEach((k, v) -> merged.put(k, new LinkedHashMap<>(v)));
        if (configured instanceof Map<?, ?> items) {
            items.forEach((item, levels) -> {
                Map<String, List<Number>> target = merged.computeIfAbsent(String.valueOf(item), k -> new LinkedHashMap<>());
                ((Map<Object, Object>) levels).forEach((level, triple) -> {
                    List<Number> t = new ArrayList<>();
                    for (Object n : (List<Object>) triple) {
                        t.add(((Number) n).doubleValue());
                    }
                    target.put(String.valueOf(level), t);
                });
            });
        }
        return merged;
    }

    private static String level(String band) {
        return (band == null || band.isEmpty() ? "Simple" : band).toLowerCase(Locale.ROOT).replace(" ", "_");
    }

    private static String or(String v, String dflt) {
        return v == null || v.isEmpty() ? dflt : v;
    }

    private static List<Doc> of(Map<String, List<Doc>> entities, String name) {
        return entities.getOrDefault(name, List.of());
    }

    /**
     * The estimate as written to {@code effort.json}. {@code componentOf} maps a file path to its
     * component (libraries list files).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> estimate(Map<String, Map<String, List<Number>>> weights, Map<String, List<Doc>> entities,
            Map<String, String> componentOf) {
        Map<List<String>, Integer> counts = new TreeMap<>((a, b) -> {
            for (int i = 0; i < 3; i++) {
                int c = PyJson.compareCodePoints(a.get(i), b.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        java.util.function.BiConsumer<String, List<String>> count = (comp, key) -> counts.merge(
                List.of(comp, key.get(0), key.get(1)), 1, Integer::sum);
        for (Doc s : of(entities, "services")) {
            if (!"batch-job".equals(s.str("kind"))) {
                count.accept(or(s.str("component"), SHARED), List.of("service", level(s.str("complexity"))));
            }
        }
        for (Doc sc : of(entities, "screens")) {
            if (!sc.bool("partial")) {
                count.accept(or(sc.str("component"), SHARED), List.of("screen", level(sc.str("complexity"))));
            }
        }
        for (Doc j : of(entities, "batch_jobs")) {
            if (!"box".equals(j.str("kind"))) {
                count.accept(or(j.str("component"), SHARED), List.of("batch_job", level(j.str("complexity"))));
            }
        }
        Set<List<String>> seenSql = new HashSet<>();
        for (Doc st : of(entities, "sql_statements")) {
            String grade = st.str("pg_grade");
            if (grade == null || grade.isEmpty()) {
                continue;
            }
            String comp = or(st.str("component"), SHARED);
            if (!seenSql.add(List.of(comp, st.str("text")))) { // the same statement converts once
                continue;
            }
            count.accept(comp, List.of("sql", grade));
        }
        for (Doc o : of(entities, "db_objects")) {
            String grade = o.str("pg_grade");
            if (MANUAL_OBJECTS.contains(o.str("object_type")) && grade != null && !grade.isEmpty()) {
                count.accept(DATABASE, List.of("stored_procedure", grade));
            }
        }
        for (Doc i : of(entities, "integrations")) {
            List<Object> comps = i.list("components");
            count.accept(comps.size() == 1 ? String.valueOf(comps.get(0)) : SHARED, List.of("integration", "each"));
        }
        for (Doc lib : of(entities, "libraries")) {
            Object rec = lib.get("recommendation");
            Map<String, Object> r = rec instanceof Doc d ? d.toMap() : rec instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            if (r == null) {
                continue;
            }
            TreeSet<String> comps = new TreeSet<>(PyJson::compareCodePoints);
            for (Object f : lib.list("where_used")) {
                String c = componentOf.get(String.valueOf(f));
                if (c != null && !c.isEmpty()) {
                    comps.add(c);
                }
            }
            count.accept(comps.size() == 1 ? comps.first() : SHARED, List.of("library", String.valueOf(r.get("action"))));
        }

        List<Map<String, Object>> lines = new ArrayList<>();
        Map<String, Map<String, Object>> byComponent = new LinkedHashMap<>();
        for (Map.Entry<List<String>, Integer> e : counts.entrySet()) {
            String comp = e.getKey().get(0);
            String item = e.getKey().get(1);
            String lvl = e.getKey().get(2);
            List<Number> triple = weights.getOrDefault(item, Map.of()).get(lvl);
            if (triple == null) {
                continue; // a level without a weight is not priced
            }
            int n = e.getValue();
            double low = PyNum.round(n * triple.get(0).doubleValue(), 2);
            double likely = PyNum.round(n * triple.get(1).doubleValue(), 2);
            double high = PyNum.round(n * triple.get(2).doubleValue(), 2);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("component", comp);
            line.put("item", item);
            line.put("level", lvl);
            line.put("count", (long) n);
            line.put("low", low);
            line.put("likely", likely);
            line.put("high", high);
            lines.add(line);
            Map<String, Object> per = byComponent.computeIfAbsent(comp, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("low", 0.0);
                m.put("likely", 0.0);
                m.put("high", 0.0);
                return m;
            });
            per.put("low", (Double) per.get("low") + low);
            per.put("likely", (Double) per.get("likely") + likely);
            per.put("high", (Double) per.get("high") + high);
        }
        for (Map<String, Object> per : byComponent.values()) {
            per.replaceAll((k, v) -> PyNum.round((Double) v, 2));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", "estimate — weights from config");
        out.put("unit", "person-days");
        out.put("low", PyNum.round(PyNum.sum(lines.stream().map(l -> (Double) l.get("low")).toList()), 2));
        out.put("likely", PyNum.round(PyNum.sum(lines.stream().map(l -> (Double) l.get("likely")).toList()), 2));
        out.put("high", PyNum.round(PyNum.sum(lines.stream().map(l -> (Double) l.get("high")).toList()), 2));
        out.put("by_component", byComponent);
        out.put("lines", lines);
        out.put("weights", weights);
        return out;
    }
}
