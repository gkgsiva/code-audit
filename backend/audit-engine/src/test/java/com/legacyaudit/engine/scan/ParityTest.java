package com.legacyaudit.engine.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.legacyaudit.model.config.ConfigLoader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every sample scanned by the Java engine gives what the Python tool wrote for it
 * ({@code parity/<sample>/}): the same inventory file byte for byte, the same components, the
 * same coverage findings and input coverage. Fields later phases fill (component type, roles and
 * entry points from the linker; vulnerability-source coverage) are left out of the comparison.
 */
class ParityTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> LATER_COMPONENT_FIELDS = Set.of("type", "roles", "entry_points");
    private static final Set<String> LATER_COVERAGE_KINDS = Set.of("dependency-check", "osv-scanner");

    static Stream<String> samples() throws Exception {
        try (Stream<Path> dirs = Files.list(SAMPLES)) {
            return dirs.filter(d -> Files.exists(d.resolve("audit.yaml"))).map(d -> d.getFileName().toString()).sorted().toList().stream();
        }
    }

    private static String fixture(String sample, String entity) throws Exception {
        try (InputStream in = ParityTest.class.getResourceAsStream("/parity/" + sample + "/" + entity + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** id -> record, with the fields filled by later phases removed. */
    private static Map<String, JsonNode> byId(String json, Set<String> drop, Set<String> dropKinds) {
        Map<String, JsonNode> out = new TreeMap<>();
        for (JsonNode n : JSON.readTree(json)) {
            if (n.has("kind") && dropKinds.contains(n.get("kind").asString())) {
                continue;
            }
            ObjectNode o = (ObjectNode) n.deepCopy();
            drop.forEach(o::remove);
            out.put(o.get("id").asString(), o);
        }
        return out;
    }

    @ParameterizedTest
    @MethodSource("samples")
    void sampleMatchesThePythonTool(String sample, @TempDir Path out) throws Exception {
        var result = Scanner.run(ConfigLoader.load(SAMPLES.resolve(sample).resolve("audit.yaml")),
                new Scanner.Options(true, out, null, null, null));
        Path data = result.runDir().resolve("data");
        assertEquals(fixture(sample, "inventory"), Files.readString(data.resolve("inventory.json")), "inventory.json");
        assertEquals(byId(fixture(sample, "components"), LATER_COMPONENT_FIELDS, Set.of()),
                byId(Files.readString(data.resolve("components.json")), LATER_COMPONENT_FIELDS, Set.of()), "components");
        assertEquals(byId(fixture(sample, "findings"), Set.of(), Set.of()),
                byId(Files.readString(data.resolve("findings.json")), Set.of(), Set.of()), "coverage findings");
        assertEquals(byId(fixture(sample, "input_coverage"), Set.of(), LATER_COVERAGE_KINDS),
                byId(Files.readString(data.resolve("input_coverage.json")), Set.of(), LATER_COVERAGE_KINDS), "input coverage");
        assertEquals(List.of(), result.run().warnings);
    }
}
