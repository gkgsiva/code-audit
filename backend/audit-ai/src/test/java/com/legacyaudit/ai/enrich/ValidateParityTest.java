package com.legacyaudit.ai.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The validator says what the Python tool said ({@code validate-parity/expected.json}) for each
 * case of {@code validate-parity/cases.json}: a sample run with its enrichment (the report parity
 * fixtures), some edits, then {@code validate}; messages, checked and stamped stages and
 * {@code status.json} must be equal, step by step.
 */
public class ValidateParityTest {

    private static final Path FIXTURES = Path.of("src", "test", "resources", "validate-parity");
    private static final Path RUNS = Path.of("..", "audit-reports", "src", "test", "resources", "report-parity");
    private static final Path SAMPLES = Path.of("..", "..", "samples");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    static Stream<Map<String, Object>> cases() throws IOException {
        return ((List<Map<String, Object>>) JSON.readValue(Files.readString(FIXTURES.resolve("cases.json")), List.class)).stream();
    }

    /** Unpacks a fixture run (sample paths put back) and returns its run folder. */
    public static Path unpack(String set, Path dest) throws IOException {
        String sample = set.substring(set.indexOf('/') + 1);
        String samplePath = SAMPLES.resolve(sample).toAbsolutePath().normalize().toString().replace('\\', '/');
        String runId = null;
        try (InputStream in = Files.newInputStream(RUNS.resolve(set).resolve("run.zip")); ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (e.isDirectory()) {
                    continue;
                }
                Path target = dest.resolve("runs").resolve(e.getName());
                Files.createDirectories(target.getParent());
                byte[] data = z.readAllBytes();
                if (e.getName().endsWith("/run.json")) {
                    data = new String(data, StandardCharsets.UTF_8).replace("@SAMPLE@", samplePath).getBytes(StandardCharsets.UTF_8);
                }
                Files.write(target, data);
                runId = e.getName().substring(0, e.getName().indexOf('/'));
            }
        }
        return dest.resolve("runs").resolve(runId);
    }

    /** {@code dir} as an absolute path, emptied. */
    public static Path fresh(Path dir) throws IOException {
        Path abs = dir.toAbsolutePath();
        rmtree(abs);
        return abs;
    }

    static void rmtree(Path p) throws IOException {
        if (Files.exists(p)) {
            try (Stream<Path> walk = Files.walk(p)) {
                for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(f);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void apply(Path runDir, Map<String, Object> e) throws IOException {
        if (e.containsKey("file")) {
            Path p = runDir.resolve((String) e.get("file"));
            if (e.containsKey("content")) {
                Files.writeString(p, PyJson.dumps(e.get("content"), false));
            } else {
                List<Object> d = JSON.readValue(Files.readString(p), List.class);
                d.add(e.get("append"));
                Files.writeString(p, PyJson.dumps(d, false));
            }
            return;
        }
        Path p = runDir.resolve("enrichment").resolve(e.get("stage") + ".json");
        if (e.containsKey("replace")) {
            Files.writeString(p, PyJson.dumps(e.get("replace"), false));
            return;
        }
        Object d = JSON.readValue(Files.readString(p), Object.class);
        Object node = d;
        List<Object> path = (List<Object>) e.get("set");
        for (Object k : path.subList(0, path.size() - 1)) {
            node = k instanceof Number n ? ((List<Object>) node).get(n.intValue()) : ((Map<String, Object>) node).get(k);
        }
        Object last = path.getLast();
        if (last instanceof Number n) {
            ((List<Object>) node).set(n.intValue(), e.get("value"));
        } else {
            ((Map<String, Object>) node).put((String) last, e.get("value"));
        }
        Files.writeString(p, PyJson.dumps(d, false));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    @SuppressWarnings("unchecked")
    void validatorAgreesWithThePythonTool(Map<String, Object> c) throws IOException {
        String name = (String) c.get("name");
        Map<String, Object> expected = JSON.readValue(Files.readString(FIXTURES.resolve("expected.json")), Map.class);
        Path dest = Path.of("target", "validate-parity-runs", name.replaceAll("[^a-z0-9]+", "-")).toAbsolutePath();
        rmtree(dest);
        Path runDir = unpack((String) c.get("set"), dest);
        List<Object> got = new ArrayList<>();
        for (Map<String, Object> step : (List<Map<String, Object>>) c.get("steps")) {
            for (Map<String, Object> e : (List<Map<String, Object>>) step.get("edits")) {
                apply(runDir, e);
            }
            Validator.Report rep = Validator.validate(runDir, (List<String>) step.get("stages"), Boolean.TRUE.equals(step.get("restamp")));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("messages", rep.messages.stream().map(Object::toString).toList());
            s.put("checked", rep.checked);
            s.put("stamped", rep.stamped);
            s.put("status", rep.status);
            got.add(s);
        }
        assertEquals(PyJson.pretty(expected.get(name)), PyJson.pretty(got), name);
    }
}
