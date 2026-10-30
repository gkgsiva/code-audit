package com.legacyaudit.engine.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.legacyaudit.model.config.ConfigEdits;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The settings page's file edits and component picker against what the Python tool's
 * {@code workspace.py} wrote (recorded in {@code workspace-parity/workspace.json}): audit.yaml
 * blocks replaced with comments kept, the overrides file after ticking and unticking
 * components, and the pre-scan of every sample.
 */
class WorkspaceParityTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples");
    private static Map<String, Object> expected;

    @SuppressWarnings("unchecked")
    static synchronized Map<String, Object> expected() throws IOException {
        if (expected == null) {
            try (var in = WorkspaceParityTest.class.getResourceAsStream("/workspace-parity/workspace.json")) {
                expected = JsonMapper.builder().build().readValue(in, Map.class);
            }
        }
        return expected;
    }

    @Test
    @SuppressWarnings("unchecked")
    void topLevelBlocksAreReplacedAsPythonDid() throws Exception {
        Map<String, String> texts = Map.of(
                "sample", Files.readString(SAMPLES.resolve("struts-oracle-app/audit.yaml")),
                "empty", "",
                "comments", "# head\napplication:  # the app\n  name: x\n\n# tail comment\nexclude:\n  - a/**\n\ntarget:\n  profile: none\n",
                "inline", "application:\n  name: x\nfull_scan: false   # keep me\n");
        int n = 0;
        for (Map<String, Object> c : (List<Map<String, Object>>) expected().get("set_top_level")) {
            String got = ConfigEdits.setTopLevel(texts.get((String) c.get("text")), (String) c.get("key"), c.get("value"));
            assertEquals(c.get("out"), got, c.get("text") + " / " + c.get("key") + " = " + c.get("value"));
            n++;
        }
        assertEquals(40, n);
    }

    @Test
    @SuppressWarnings("unchecked")
    void componentChoicesWriteTheSameOverrides(@TempDir Path tmp) throws Exception {
        for (Map<String, Object> c : (List<Map<String, Object>>) expected().get("choices")) {
            Path ov = tmp.resolve(c.get("sample") + "-overrides.yaml");
            if (c.get("before") != null) {
                Files.writeString(ov, (String) c.get("before"));
            }
            Map<String, Boolean> inc = (Map<String, Boolean>) c.get("included");
            Map<String, String> types = (Map<String, String>) c.get("types");
            ConfigEdits.setComponentChoices(ov, inc, types);
            assertEquals(c.get("first"), Files.readString(ov), c.get("sample") + " (untick)");
            Map<String, Boolean> back = new java.util.LinkedHashMap<>();
            inc.keySet().forEach(k -> back.put(k, true));
            Map<String, String> clear = new java.util.LinkedHashMap<>();
            types.keySet().forEach(k -> clear.put(k, null));
            ConfigEdits.setComponentChoices(ov, back, clear);
            assertEquals(c.get("second"), Files.readString(ov), c.get("sample") + " (tick again)");
        }
        Path bad = tmp.resolve("bad.yaml");
        assertThrows(ConfigException.class, () -> ConfigEdits.setComponentChoices(bad, Map.of(), Map.of("x", "nope")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void prescanFindsTheSameComponents(@TempDir Path tmp) throws Exception {
        Map<String, Object> all = (Map<String, Object>) expected().get("prescan");
        for (Map.Entry<String, Object> e : all.entrySet()) {
            Path ws = tmp.resolve(e.getKey());
            copy(SAMPLES.resolve(e.getKey()), ws);
            LoadedConfig loaded = ConfigLoader.load(ws.resolve("audit.yaml"));
            Map<String, Object> got = Prescan.run(loaded);
            ((List<Map<String, Object>>) got.get("roots")).forEach(r -> r.remove("path"));
            assertEquals(PyJson.pretty(e.getValue(), false), PyJson.pretty(got, false), e.getKey());
        }
    }

    @Test
    void configTextIsCheckedBeforeItIsWritten(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("audit.yaml");
        Files.writeString(cfg, "application:\n  name: x\n");
        assertThrows(ConfigException.class, () -> ConfigEdits.writeConfigText(cfg, "application: [\n"));
        assertThrows(ConfigException.class, () -> ConfigEdits.writeConfigText(cfg, "application:\n  nme: x\n"));
        assertThrows(ConfigException.class, () -> ConfigEdits.updateConfig(cfg, Map.of("full_scan", "maybe")));
        assertEquals("application:\n  name: x\n", Files.readString(cfg));
        ConfigEdits.updateConfig(cfg, Map.of("exclude", List.of("a/**")));
        assertEquals("application:\n  name: x\n\nexclude:\n- a/**\n", Files.readString(cfg));
    }

    static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path p : files.sorted(Comparator.naturalOrder()).toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t);
                }
            }
        }
    }
}
