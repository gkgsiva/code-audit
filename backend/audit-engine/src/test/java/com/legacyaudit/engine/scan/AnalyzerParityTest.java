package com.legacyaudit.engine.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.legacyaudit.engine.analyzers.Analyzer;
import com.legacyaudit.engine.analyzers.Analyzers;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.json.PyJson;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every analyzer ported so far finds in every sample file exactly what the Python analyzer of
 * the same name found ({@code parity/<sample>/analyzers.json}, taken from the Python tool's file
 * cache): the same files, entities, symbols, references, coverage, units and rule hits.
 */
class AnalyzerParityTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static Stream<String> samples() throws Exception {
        return ParityTest.samples();
    }

    @ParameterizedTest
    @MethodSource("samples")
    void analyzersMatchThePythonTool(String sample, @TempDir Path out) throws Exception {
        JsonNode expected;
        try (InputStream in = getClass().getResourceAsStream("/parity/" + sample + "/analyzers.json")) {
            expected = JSON.readTree(in);
        }
        var result = Scanner.run(ConfigLoader.load(SAMPLES.resolve(sample).resolve("audit.yaml")),
                new Scanner.Options(true, out, null, null, null));
        Map<String, Map<String, JsonNode>> actual = new TreeMap<>();
        for (Scanner.FileFacts ff : result.fileFacts()) {
            actual.computeIfAbsent(ff.analyzer(), k -> new TreeMap<>()).put(ff.path(), JSON.readTree(PyJson.compact(ff.facts().dump())));
        }
        List<String> problems = new ArrayList<>();
        for (Analyzer a : Analyzers.registered()) {
            Map<String, JsonNode> want = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = expected.properties().iterator(); it.hasNext();) {
                var e = it.next();
                if (e.getValue().has(a.name())) {
                    want.put(e.getKey(), e.getValue().get(a.name()));
                }
            }
            Map<String, JsonNode> got = actual.getOrDefault(a.name(), Map.of());
            if (!want.keySet().equals(got.keySet())) {
                TreeSet<String> missing = new TreeSet<>(want.keySet());
                missing.removeAll(got.keySet());
                TreeSet<String> extra = new TreeSet<>(got.keySet());
                extra.removeAll(want.keySet());
                problems.add(a.name() + ": files missing " + missing + ", extra " + extra);
            }
            for (var e : want.entrySet()) {
                JsonNode g = got.get(e.getKey());
                if (g != null && !g.equals(e.getValue())) {
                    problems.add(a.name() + " on " + e.getKey() + ":\n  python: " + e.getValue() + "\n  java:   " + g);
                }
            }
        }
        if (!problems.isEmpty()) {
            fail(sample + ": " + problems.size() + " difference(s)\n" + String.join("\n", problems));
        }
        assertEquals(0, problems.size());
    }
}
