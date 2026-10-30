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
import java.util.Objects;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;

/** Compare two runs: counts and added / removed / changed records per entity. */
public final class Diff {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> LABEL_KEYS = List.of("path", "name", "kind", "target", "rule_id", "artifact", "file", "id");

    private Diff() {}

    /** A short name for a record: its path, name, kind... or id. */
    public static String label(Map<String, Object> record) {
        for (String k : LABEL_KEYS) {
            Object v = record.get(k);
            if (v != null && !"".equals(v) && !Boolean.FALSE.equals(v)) {
                return String.valueOf(v);
            }
        }
        return String.valueOf(record.get("id"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> byId(Path dataDir, String entity) throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Path p = dataDir.resolve(entity + ".json");
        if (Files.exists(p)) {
            for (Object o : JSON.readValue(Files.readString(p, StandardCharsets.UTF_8), List.class)) {
                Map<String, Object> r = (Map<String, Object>) o;
                out.put(String.valueOf(r.get("id")), r);
            }
        }
        return out;
    }

    private static TreeSet<String> sorted() {
        return new TreeSet<>(PyJson::compareCodePoints);
    }

    private static Map<String, Object> ref(String id, Map<String, Object> record) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label(record));
        return m;
    }

    /** The diff of run folder {@code a} to run folder {@code b}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> runs(Path a, Path b) throws IOException {
        Map<String, Object> entities = new LinkedHashMap<>();
        for (String entity : Summary.ENTITIES) {
            Map<String, Map<String, Object>> ra = byId(a.resolve("data"), entity);
            Map<String, Map<String, Object>> rb = byId(b.resolve("data"), entity);
            TreeSet<String> added = sorted();
            added.addAll(rb.keySet());
            added.removeAll(ra.keySet());
            TreeSet<String> removed = sorted();
            removed.addAll(ra.keySet());
            removed.removeAll(rb.keySet());
            TreeSet<String> both = sorted();
            both.addAll(ra.keySet());
            both.retainAll(rb.keySet());
            List<Object> changed = new ArrayList<>();
            for (String i : both) {
                Map<String, Object> x = ra.get(i);
                Map<String, Object> y = rb.get(i);
                if (!x.equals(y)) {
                    TreeSet<String> keys = sorted();
                    keys.addAll(x.keySet());
                    keys.addAll(y.keySet());
                    Map<String, Object> fields = new LinkedHashMap<>();
                    for (String k : keys) {
                        if (!Objects.equals(x.get(k), y.get(k))) {
                            fields.put(k, java.util.Arrays.asList(x.get(k), y.get(k)));
                        }
                    }
                    Map<String, Object> c = ref(i, y);
                    c.put("fields", fields);
                    changed.add(c);
                }
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("count_a", (long) ra.size());
            d.put("count_b", (long) rb.size());
            d.put("added", added.stream().map(i -> (Object) ref(i, rb.get(i))).toList());
            d.put("removed", removed.stream().map(i -> (Object) ref(i, ra.get(i))).toList());
            d.put("changed", changed);
            entities.put(entity, d);
        }
        Map<String, Object> infoA = JSON.readValue(Files.readString(a.resolve("run.json"), StandardCharsets.UTF_8), Map.class);
        Map<String, Object> infoB = JSON.readValue(Files.readString(b.resolve("run.json"), StandardCharsets.UTF_8), Map.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_a", infoA.get("run_id"));
        out.put("run_b", infoB.get("run_id"));
        out.put("sources_a", infoA.getOrDefault("sources", List.of()));
        out.put("sources_b", infoB.getOrDefault("sources", List.of()));
        out.put("entities", entities);
        return out;
    }
}
