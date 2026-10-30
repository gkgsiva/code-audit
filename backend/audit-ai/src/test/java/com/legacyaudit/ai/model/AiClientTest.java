package com.legacyaudit.ai.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.ConfigLoader;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Model settings, the requests each provider family gets, and reading JSON out of an answer. */
class AiClientTest {

    /** A transport that records requests and answers from a queue (an AiError is thrown). */
    static final class Recorder implements AiClient.Transport {
        final LinkedList<Object> replies = new LinkedList<>();
        final List<Object[]> calls = new ArrayList<>();

        Recorder(Object... replies) {
            this.replies.addAll(List.of(replies));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> post(String url, Map<String, String> headers, Map<String, Object> body, AiSettings s) {
            calls.add(new Object[] {url, headers, body});
            Object reply = replies.removeFirst();
            if (reply instanceof AiError e) {
                throw e;
            }
            return (Map<String, Object>) reply;
        }
    }

    static Map<String, Object> chat(String text) {
        return Map.of("choices", List.of(Map.of("message", Map.of("content", text))));
    }

    static AuditConfig config(Path dir, String ai) throws Exception {
        Files.writeString(dir.resolve("audit.yaml"), "application:\n  name: t\n" + (ai.isEmpty() ? "" : "ai:\n" + ai));
        return ConfigLoader.load(dir.resolve("audit.yaml")).config();
    }

    static AiSettings settings(AuditConfig cfg, Map<String, String> env) {
        return AiSettings.of(cfg, env::get, null, null, null, null);
    }

    @Test
    void settingsComeFromConfigThenEnvironment(@TempDir Path dir) throws Exception {
        AuditConfig cfg = config(dir, "  provider: openai\n  model: gpt-x\n");
        Map<String, String> env = new HashMap<>();
        assertEquals("openai:gpt-x", settings(cfg, env).label());
        env.put("LEGACY_AUDIT_AI_PROVIDER", "ollama");
        env.put("LEGACY_AUDIT_AI_MODEL", "llama3.1");
        AiSettings s = settings(cfg, env);
        assertEquals(List.of("ollama", "llama3.1", "http://localhost:11434/v1"), List.of(s.provider(), s.model(), s.url()));
        assertEquals("qwen2.5", AiSettings.of(cfg, env::get, null, "qwen2.5", null, null).model()); // the command line wins
        env.put("LEGACY_AUDIT_AI_PROVIDER", "nope");
        AiError err = assertThrows(AiError.class, () -> settings(cfg, env));
        assertTrue(err.getMessage().startsWith("Unknown AI provider 'nope'"));
        AiSettings dflt = settings(config(dir, ""), Map.of());
        assertEquals(0.1, dflt.temperature());
        assertEquals(List.of(16000, 150_000, 600, 2), List.of(dflt.maxOutputTokens(), dflt.contextChars(), dflt.timeoutSeconds(), dflt.repairRounds()));
        assertEquals(null, settings(config(dir, "  temperature: null\n"), Map.of()).temperature());
    }

    @Test
    void copilotAndMissingSettingsExplainWhatToDo(@TempDir Path dir) throws Exception {
        AiError e = assertThrows(AiError.class, () -> new AiClient(settings(config(dir, ""), Map.of()), new Recorder(), k -> null));
        assertTrue(e.getMessage().contains("/audit-enrich"));
        e = assertThrows(AiError.class, () -> new AiClient(settings(config(dir, "  provider: anthropic\n"), Map.of()), new Recorder(), k -> null));
        assertTrue(e.getMessage().contains("No model"));
        AiClient client = new AiClient(settings(config(dir, "  provider: openai\n  model: m\n"), Map.of()), new Recorder(), k -> null);
        e = assertThrows(AiError.class, () -> client.complete("s", "u"));
        assertTrue(e.getMessage().contains("OPENAI_API_KEY"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void openAiCompatibleRequest(@TempDir Path dir) throws Exception {
        Recorder rec = new Recorder(chat("{\"ok\": true}"));
        AiClient client = new AiClient(settings(config(dir, "  provider: openai\n  model: gpt-x\n"), Map.of()), rec,
                Map.of("OPENAI_API_KEY", "sk-test")::get);
        assertEquals("{\"ok\": true}", client.complete("sys", "user"));
        Object[] call = rec.calls.get(0);
        assertEquals("https://api.openai.com/v1/chat/completions", call[0]);
        assertEquals(Map.of("Authorization", "Bearer sk-test"), call[1]);
        Map<String, Object> body = (Map<String, Object>) call[2];
        assertEquals("gpt-x", body.get("model"));
        assertEquals(Map.of("role", "system", "content", "sys"), ((List<?>) body.get("messages")).get(0));
        assertEquals(Map.of("type", "json_object"), body.get("response_format"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedParametersAreDropped(@TempDir Path dir) throws Exception {
        Recorder rec = new Recorder(new AiError("HTTP 400 from x: Unsupported parameter: 'max_tokens'"),
                new AiError("HTTP 400 from x: 'temperature' does not support 0.1"), chat("{}"));
        AiClient client = new AiClient(settings(config(dir, "  provider: openai\n  model: m\n  base_url: http://gw.local/v1\n"), Map.of()),
                rec, k -> null);
        client.complete("s", "u"); // a gateway without a key is allowed
        Object[] last = rec.calls.getLast();
        Map<String, Object> body = (Map<String, Object>) last[2];
        assertTrue(body.containsKey("max_completion_tokens") && !body.containsKey("max_tokens"));
        assertFalse(body.containsKey("temperature"));
        assertEquals(Map.of(), last[1]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ollamaAzureAndAnthropicRequests(@TempDir Path dir) throws Exception {
        Recorder rec = new Recorder(chat("{}"));
        new AiClient(settings(config(dir, "  provider: ollama\n  model: llama3.1\n"), Map.of()), rec, k -> null).complete("s", "u");
        assertEquals("http://localhost:11434/v1/chat/completions", rec.calls.get(0)[0]);
        assertEquals(Map.of(), rec.calls.get(0)[1]);

        rec = new Recorder(chat("{}"));
        new AiClient(settings(config(dir, "  provider: azure-openai\n  model: gpt4o-prod\n  base_url: https://r.openai.azure.com\n"), Map.of()),
                rec, Map.of("AZURE_OPENAI_API_KEY", "az")::get).complete("s", "u");
        String url = (String) rec.calls.get(0)[0];
        assertTrue(url.startsWith("https://r.openai.azure.com/openai/deployments/gpt4o-prod/chat/") && url.contains("api-version="));
        assertEquals(Map.of("api-key", "az"), rec.calls.get(0)[1]);
        assertFalse(((Map<String, Object>) rec.calls.get(0)[2]).containsKey("model"));

        rec = new Recorder(Map.of("content", List.of(Map.of("type", "text", "text", "{\"a\": 1}"))));
        String out = new AiClient(settings(config(dir, "  provider: anthropic\n  model: claude-x\n"), Map.of()), rec,
                Map.of("ANTHROPIC_API_KEY", "ak")::get).complete("s", "u");
        assertEquals("{\"a\": 1}", out);
        assertEquals("https://api.anthropic.com/v1/messages", rec.calls.get(0)[0]);
        assertEquals("ak", ((Map<String, String>) rec.calls.get(0)[1]).get("x-api-key"));
        assertEquals("s", ((Map<String, Object>) rec.calls.get(0)[2]).get("system"));
    }

    @Test
    void extractJsonToleratesFencesAndText() {
        assertEquals(Map.of("a", 1), AiClient.extractJson("```json\n{\"a\": 1}\n```"));
        assertEquals(Map.of("a", List.of(1, 2)), AiClient.extractJson("Here it is: {\"a\": [1, 2]} hope it helps"));
        assertThrows(AiError.class, () -> AiClient.extractJson("no json here"));
    }

    @Test
    void proxyFromTheEnvironment() {
        Map<String, String> env = Map.of("HTTPS_PROXY", "http://proxy.corp:3128", "NO_PROXY", "localhost,.internal");
        var sel = AiClient.proxySelector(env::get);
        Proxy p = sel.select(URI.create("https://api.openai.com/v1")).get(0);
        assertEquals("proxy.corp:3128", p.address().toString().replace("/<unresolved>", ""));
        assertEquals(Proxy.NO_PROXY, sel.select(URI.create("http://localhost:11434/v1")).get(0));
        assertEquals(Proxy.NO_PROXY, sel.select(URI.create("https://gw.internal/v1")).get(0));
    }
}
