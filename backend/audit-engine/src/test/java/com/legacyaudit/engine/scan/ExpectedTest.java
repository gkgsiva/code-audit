package com.legacyaudit.engine.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legacyaudit.engine.store.Summary;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.facts.Component;
import com.legacyaudit.model.facts.Doc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** Every sample app's counts match its {@code expected.yaml} (the Python tool's test_samples). */
class ExpectedTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static Stream<String> samples() throws Exception {
        return ParityTest.samples();
    }

    /** YAML and JSON numbers both as longs, so the two compare. */
    @SuppressWarnings("unchecked")
    private static Object norm(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), norm(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(ExpectedTest::norm).toList();
        }
        if (v instanceof Number n && !(v instanceof Double)) {
            return n.longValue();
        }
        return v;
    }

    private static List<String> sorted(List<String> items) {
        List<String> out = new ArrayList<>(items);
        out.sort(com.legacyaudit.model.json.PyJson::compareCodePoints);
        return out;
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @MethodSource("samples")
    void countsMatchExpectedYaml(String sample) throws Exception {
        Path dir = SAMPLES.resolve(sample);
        Map<String, Object> expected = (Map<String, Object>) ConfigLoader.parseYaml(Files.readString(dir.resolve("expected.yaml")), "expected.yaml");
        var result = Scanner.run(ConfigLoader.load(dir.resolve("audit.yaml")),
                new Scanner.Options(true, Path.of("target", "expected-runs", sample), null, null, null));
        Map<String, Object> summary = JSON.readValue(Files.readString(result.runDir().resolve("data").resolve("audit-summary.json")), Map.class);

        Map<String, Object> inv = (Map<String, Object>) expected.get("inventory");
        Map<String, Object> totals = (Map<String, Object>) summary.get("totals");
        assertEquals(norm(inv.get("files")), norm(totals.get("files")));
        assertEquals(norm(inv.get("input_files")), norm(totals.get("input_files")));
        assertEquals(norm(inv.get("loc")), norm(Map.of("code", totals.get("loc_code"), "comment", totals.get("loc_comment"),
                "blank", totals.get("loc_blank"))));
        Map<String, Object> byLang = new LinkedHashMap<>();
        ((Map<String, Map<String, Object>>) summary.get("by_language")).forEach((lang, v) -> byLang.put(lang,
                Map.of("files", v.get("files"), "code", v.get("loc_code"), "comment", v.get("loc_comment"), "blank", v.get("loc_blank"))));
        assertEquals(norm(inv.get("by_language")), norm(byLang));
        Map<String, Object> byCat = new LinkedHashMap<>();
        ((Map<String, Map<String, Object>>) summary.get("by_category")).forEach((k, v) -> byCat.put(k, v.get("files")));
        assertEquals(norm(inv.get("by_category")), norm(byCat));

        List<Object> comps = new ArrayList<>();
        Map<String, Object> roles = new LinkedHashMap<>();
        Map<String, Object> groups = new LinkedHashMap<>();
        for (Component c : result.components()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name);
            m.put("type", c.type.value());
            m.put("files", c.files);
            m.put("detected_by", c.detectedBy);
            comps.add(m);
            roles.put(c.name, c.roles);
            if (!c.buildGroup.isEmpty()) {
                groups.put(c.name, c.buildGroup);
            }
        }
        assertEquals(norm(expected.get("components")), norm(comps));
        if (expected.containsKey("component_roles")) {
            assertEquals(norm(expected.get("component_roles")), norm(roles));
        }
        if (expected.containsKey("unscheduled_batches")) {
            assertEquals(norm(expected.get("unscheduled_batches")), norm(summary.get("unscheduled_batches")));
        }
        if (expected.containsKey("unscheduled_batch_entries")) {
            Map<String, Object> names = new LinkedHashMap<>();
            ((Map<String, List<Map<String, Object>>>) summary.get("unscheduled_batch_entries"))
                    .forEach((comp, items) -> names.put(comp, items.stream().map(i -> i.get("name")).toList()));
            assertEquals(norm(expected.get("unscheduled_batch_entries")), norm(names));
        }
        if (expected.containsKey("build_groups")) {
            assertEquals(norm(expected.get("build_groups")), norm(groups));
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        result.run().coverage.forEach(c -> coverage.put(c.kind(), c.status().value()));
        assertEquals(norm(expected.get("coverage")), norm(coverage));

        Map<String, Object> want = new LinkedHashMap<>((Map<String, Object>) expected.get("counts"));
        want.putAll((Map<String, Object>) expected.get("assessment"));
        assertEquals(norm(want), norm(summary.get("counts")));
        Map<String, List<Doc>> facts = result.linked();
        assertEquals(expected.get("services"), sorted(facts.get("services").stream().map(s -> s.str("kind") + ": " + s.str("name")).toList()));
        assertEquals(expected.get("screens"), sorted(facts.get("screens").stream()
                .map(s -> s.str("name") + (s.bool("partial") ? " (partial)" : "")).toList()));
        assertEquals(expected.get("integrations"), sorted(facts.get("integrations").stream()
                .map(i -> i.str("type") + " " + i.str("target") + " [" + i.str("direction") + "]").toList()));
        if (expected.containsKey("web_services")) {
            assertEquals(expected.get("web_services"), sorted(facts.get("web_services").stream()
                    .map(w -> w.str("side") + " " + w.str("style") + ": " + w.str("name") + " (" + w.list("operations").size() + " ops)").toList()));
        }
        if (expected.containsKey("integration_purposes")) {
            Map<String, Object> purposes = new LinkedHashMap<>();
            facts.get("integrations").forEach(i -> purposes.put(i.str("type") + " " + i.str("target"), i.str("purpose")));
            assertEquals(norm(expected.get("integration_purposes")), norm(purposes));
        }
        assertEquals(expected.get("batch_jobs"), sorted(facts.get("batch_jobs").stream()
                .map(j -> j.str("scheduler") + " " + j.str("kind") + ": " + j.str("name")).toList()));
        assertEquals(norm(expected.get("unresolved_references")), (long) result.run().unresolvedReferences);
        assertFalse(result.run().analyzers.isEmpty());
        for (Object cov : result.run().analyzers.values()) {
            Map<String, Object> m = (Map<String, Object>) cov;
            double pct = ((Number) m.get("coverage_pct")).doubleValue();
            assertTrue(((Number) m.get("files")).longValue() > 0 && pct >= 0 && pct <= 100);
        }
        assertEquals(norm(((Map<String, Object>) summary.get("counts")).get("services")), norm(Summary.counts(new Summary.Facts(
                result.inventory(), result.components(), facts, List.of(), result.coverage())).get("services")));
    }
}
