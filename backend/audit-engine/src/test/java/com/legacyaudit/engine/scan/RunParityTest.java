package com.legacyaudit.engine.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.legacyaudit.model.config.ConfigLoader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The whole run of every sample matches what the Python tool wrote ({@code parity/<sample>/run/}):
 * every entity file in the same order with the same values. Fields that a step not yet ported
 * fills are left out of the comparison, per entity.
 */
class RunParityTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Entity -> fields filled after linking (metrics, assessment). */
    static final Map<String, Set<String>> LATER = Map.ofEntries(
            Map.entry("components", Set.of()),
            Map.entry("entry_points", Set.of()),
            Map.entry("services", Set.of("complexity", "complexity_score", "fan_in", "fan_out")),
            Map.entry("screens", Set.of("complexity", "complexity_score")),
            Map.entry("sql_statements", Set.of("pg_grade", "pg_text", "reason")),
            Map.entry("db_objects", Set.of("pg_grade", "reason")),
            Map.entry("integrations", Set.of()),
            Map.entry("web_services", Set.of()),
            Map.entry("dependencies", Set.of()),
            Map.entry("batch_jobs", Set.of("complexity", "complexity_score")),
            Map.entry("libraries", Set.of("recommendation", "eol", "eol_date", "replacement", "recommended_version", "cves",
                    "max_severity", "vulnerabilities")),
            Map.entry("tech_stack", Set.of("eol", "eol_date", "replacement")));

    static Stream<String> samples() throws Exception {
        return ParityTest.samples();
    }

    private static String fixture(String sample, String entity) throws Exception {
        try (InputStream in = RunParityTest.class.getResourceAsStream("/parity/" + sample + "/run/" + entity + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<JsonNode> rows(String json, Set<String> drop) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : JSON.readTree(json)) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            drop.forEach(o::remove);
            out.add(o);
        }
        return out;
    }

    @ParameterizedTest
    @MethodSource("samples")
    void runMatchesThePythonTool(String sample) throws Exception {
        Path out = Path.of("target", "parity-runs", sample);
        var result = Scanner.run(ConfigLoader.load(SAMPLES.resolve(sample).resolve("audit.yaml")),
                new Scanner.Options(true, out, null, null, null));
        Path data = result.runDir().resolve("data");
        List<String> differ = new ArrayList<>();
        for (var e : new java.util.TreeMap<>(LATER).entrySet()) {
            if (!rows(fixture(sample, e.getKey()), e.getValue()).equals(rows(Files.readString(data.resolve(e.getKey() + ".json")), e.getValue()))) {
                differ.add(e.getKey());
            }
        }
        assertEquals(List.of(), differ, sample + ": entities that differ (Java run in " + result.runDir() + ")");
    }
}
