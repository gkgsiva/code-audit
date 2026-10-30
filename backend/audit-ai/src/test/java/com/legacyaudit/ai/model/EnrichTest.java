package com.legacyaudit.ai.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legacyaudit.ai.enrich.ValidateParityTest;
import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.model.config.AuditConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code enrich} end to end against a local fake model that answers each stage with the
 * hand-written enrichment of the sample (and, once, with a wrong id, to see the repair round):
 * every stage passes the validator with each provider type (the Phase 7 gate).
 */
class EnrichTest {

    private static final Pattern STAGE = Pattern.compile("# Stage \\d+: .*?\\(`(\\w+)`\\)");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final Map<String, String> answers = new HashMap<>();
    private final AtomicBoolean firstBad = new AtomicBoolean(true);
    private final List<String> paths = new ArrayList<>();

    /** The run with its enrichment moved out: the fake model gives it back stage by stage. */
    private Path run(String name) throws IOException {
        Path runDir = ValidateParityTest.unpack("enriched/struts-oracle-app", ValidateParityTest.fresh(Path.of("target", "enrich-runs", name)));
        Path edir = runDir.resolve("enrichment");
        try (Stream<Path> files = Files.list(edir)) {
            for (Path f : files.toList()) {
                answers.put(f.getFileName().toString().replace(".json", ""), Files.readString(f));
                Files.delete(f);
            }
        }
        Files.delete(edir);
        return runDir;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<?, ?> body = JSON.readValue(ex.getRequestBody().readAllBytes(), Map.class);
            synchronized (paths) {
                paths.add(ex.getRequestURI().toString());
            }
            List<?> messages = (List<?>) body.get("messages");
            String user = (String) ((Map<?, ?>) messages.getLast()).get("content");
            Matcher m = STAGE.matcher(user);
            String name = m.find() ? m.group(1) : "check";
            String data = answers.getOrDefault(name, "{\"ok\": true}");
            if (name.equals("flows") && firstBad.getAndSet(false)) {
                data = data.replaceFirst("\"component_ids\": \\[", "\"component_ids\": [\"component:nope\", "); // one wrong id
            }
            String text = "```json\n" + data + "\n```";
            Object reply = ex.getRequestURI().getPath().endsWith("/v1/messages")
                    ? Map.of("content", List.of(Map.of("type", "text", "text", text)))
                    : Map.of("choices", List.of(Map.of("message", Map.of("content", text))));
            byte[] out = JSON.writeValueAsBytes(reply);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AiClient client(Path dir, String provider) throws Exception {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        String base = switch (provider) {
            case "anthropic", "azure-openai" -> url;
            default -> url + "/v1";
        };
        AuditConfig cfg = AiClientTest.config(dir, "  provider: " + provider + "\n  model: fake\n  base_url: " + base + "\n");
        Map<String, String> env = Map.of("OPENAI_API_KEY", "k", "ANTHROPIC_API_KEY", "k", "AZURE_OPENAI_API_KEY", "k");
        AiSettings s = AiSettings.of(cfg, env::get, null, null, null, null);
        return new AiClient(s, null, env::get);
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "ollama", "azure-openai", "anthropic"})
    @SuppressWarnings("unchecked")
    void everyStagePassesWithEachProviderType(String provider, @TempDir Path dir) throws Exception {
        Path runDir = run(provider);
        AiClient client = client(dir, provider);
        assertEquals("fake answered correctly.", Enricher.check(client).replace(provider + ":", ""));
        List<String> said = new ArrayList<>();
        List<Enricher.StageResult> results = Enricher.enrich(runDir, Enricher.pickStages(runDir, null, false), client, said::add);
        assertEquals(Stages.STAGES.size(), results.size());
        for (Enricher.StageResult r : results) {
            assertTrue(r.ok, r.stage + ": " + r.errors);
        }
        assertTrue(said.contains("Stage 4 flows: valid after 2 round(s)"), said.toString());
        Map<String, Object> flows = JSON.readValue(Files.readString(runDir.resolve("enrichment/flows.json")), Map.class);
        Map<String, Object> meta = (Map<String, Object>) flows.get("meta");
        assertEquals(provider + ":fake", meta.get("author"));
        assertTrue(meta.containsKey("built_from"));
        Map<String, Object> status = Status.compute(runDir);
        assertEquals(List.of(), status.get("missing"));
        assertEquals(List.of(), status.get("stale"));
        assertTrue(Enricher.pickStages(runDir, null, true).isEmpty());
        String expectedPath = switch (provider) {
            case "anthropic" -> "/v1/messages";
            case "azure-openai" -> "/openai/deployments/fake/chat/completions?api-version=2024-10-21";
            default -> "/v1/chat/completions";
        };
        assertEquals(expectedPath, paths.get(0));
    }

    @Test
    void stopsWhenTheEndpointFails(@TempDir Path dir) throws Exception {
        Path runDir = run("fails");
        AuditConfig cfg = AiClientTest.config(dir, "  provider: ollama\n  model: m\n");
        AiClient client = new AiClient(AiSettings.of(cfg, k -> null, null, null, null, null),
                new AiClientTest.Recorder(new AiError("Cannot reach http://localhost:11434/v1")), k -> null);
        List<Enricher.StageResult> results = Enricher.enrich(runDir, Enricher.pickStages(runDir, List.of("overview", "flows"), false),
                client, s -> { });
        assertEquals(1, results.size());
        assertFalse(results.get(0).ok);
        assertTrue(results.get(0).errors.get(0).contains("Cannot reach"));
    }

    @Test
    void pickStagesExplainsWhatItCannotRun(@TempDir Path dir) throws Exception {
        Path runDir = run("pick");
        AiError e = org.junit.jupiter.api.Assertions.assertThrows(AiError.class, () -> Enricher.pickStages(runDir, List.of("nope"), false));
        assertEquals("No such stage: nope", e.getMessage());
        assertEquals(List.of("overview", "plan"), Enricher.pickStages(runDir, List.of("plan", "overview"), false).stream().map(Stages.Stage::name).toList());
        assertEquals(Stages.STAGES.size(), Enricher.pickStages(runDir, null, true).size()); // all missing
    }
}
