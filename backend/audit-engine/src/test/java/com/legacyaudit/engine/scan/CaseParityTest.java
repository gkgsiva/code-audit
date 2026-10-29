package com.legacyaudit.engine.scan;

import static org.junit.jupiter.api.Assertions.fail;

import com.legacyaudit.engine.analyzers.Analyzer;
import com.legacyaudit.engine.analyzers.AnalyzerContext;
import com.legacyaudit.engine.analyzers.Analyzers;
import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.ConfigReader;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.json.PyJson;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replays the analyzer calls recorded while the Python tool's own test suite ran
 * ({@code parity/cases/<analyzer>.json.gz}: the file record, its text or bytes, the configuration
 * and the facts Python returned) and checks every ported analyzer returns the same facts, or
 * fails with the same error type.
 */
class CaseParityTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static Stream<String> analyzers() {
        return Analyzers.registered().stream().map(Analyzer::name);
    }

    @ParameterizedTest
    @MethodSource("analyzers")
    void recordedCasesMatchThePythonTool(String name, @TempDir Path tmp) throws Exception {
        Analyzer analyzer = Analyzers.registered().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
        List<Map<String, Object>> cases;
        try (InputStream raw = getClass().getResourceAsStream("/parity/cases/" + name + ".json.gz")) {
            if (raw == null) {
                return;
            }
            cases = JSON.readValue(new GZIPInputStream(raw), new TypeReference<List<Map<String, Object>>>() {});
        }
        List<String> problems = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> c : cases) {
            n++;
            @SuppressWarnings("unchecked")
            FileRecord rec = FileRecord.fromMap((Map<String, Object>) c.get("record"));
            AuditConfig cfg;
            try {
                Map<String, Object> data = new LinkedHashMap<>(asMap(c.get("config")));
                cfg = ConfigReader.parse(data, "case");
            } catch (RuntimeException e) {
                problems.add("case " + n + " (" + rec.path + "): config not readable: " + e.getMessage());
                continue;
            }
            Path abs = tmp.resolve("case-" + n);
            if (c.get("bytes") instanceof String b64) {
                Files.write(abs, Base64.getDecoder().decode(b64));
            } else if (c.get("text") instanceof String text) {
                Files.writeString(abs, text);
            }
            AnalyzerContext ctx = new AnalyzerContext(cfg, abs, (String) c.get("text"), null);
            if (!analyzer.supports(rec)) {
                problems.add("case " + n + " (" + rec.path + "): not supported");
                continue;
            }
            String error = (String) c.get("error");
            String got;
            try {
                got = PyJson.compact(analyzer.analyze(rec, ctx).dump());
            } catch (Exception | StackOverflowError e) {
                if (error == null) {
                    problems.add("case " + n + " (" + rec.path + "): " + e);
                }
                continue;
            }
            if (error != null) {
                problems.add("case " + n + " (" + rec.path + "): Python failed with " + error + ", Java did not");
                continue;
            }
            JsonNode want = JSON.valueToTree(c.get("facts"));
            JsonNode have = JSON.readTree(got);
            if (!want.equals(have)) {
                problems.add("case " + n + " (" + rec.path + "):\n  python: " + want + "\n  java:   " + have);
            }
        }
        if (!problems.isEmpty()) {
            fail(name + ": " + problems.size() + " of " + cases.size() + " case(s) differ\n" + String.join("\n", problems));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
