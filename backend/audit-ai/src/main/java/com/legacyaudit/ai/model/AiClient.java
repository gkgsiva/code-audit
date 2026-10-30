package com.legacyaudit.ai.model;

import com.legacyaudit.engine.rules.Py;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls one model over HTTP: any OpenAI-compatible {@code /chat/completions} endpoint (OpenAI,
 * Gemini, Mistral, a corporate gateway...), a local Ollama, an Azure OpenAI deployment or the
 * Anthropic Messages API. It takes a system and a user prompt and returns the model's text.
 *
 * <p>The API key is read from the environment variable the settings name, at call time, and is
 * never logged or stored. The proxy comes from {@code HTTPS_PROXY} / {@code HTTP_PROXY} (and
 * {@code NO_PROXY}) like any command-line tool, else from the Java proxy settings; {@code
 * ai.ca_bundle} replaces the trusted certificates with a corporate bundle. {@code copilot} is not
 * a client: Copilot runs inside VS Code, where the analyst picks the model.
 */
public final class AiClient {

    static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final JsonMapper JSON = JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();

    /** Sends one JSON request and returns the parsed JSON answer; swapped in tests. */
    @FunctionalInterface
    public interface Transport {
        Map<String, Object> post(String url, Map<String, String> headers, Map<String, Object> body, AiSettings s);
    }

    private final AiSettings s;
    private final Transport transport;
    private final Function<String, String> env;
    private final Set<String> drop = new HashSet<>(); // parameters an endpoint rejected

    public AiClient(AiSettings s) {
        this(s, null, System::getenv);
    }

    public AiClient(AiSettings s, Transport transport, Function<String, String> env) {
        if (s.provider().equals("copilot")) {
            throw new AiError("The AI provider is 'copilot': run /audit-enrich in VS Code Copilot Chat and pick "
                    + "the model there, or set ai.provider (openai, anthropic, azure-openai, ollama) "
                    + "in audit.yaml or LEGACY_AUDIT_AI_PROVIDER to run it here.");
        }
        if (s.model() == null || s.model().isEmpty()) {
            throw new AiError("No model set for provider " + s.provider() + ": set ai.model in audit.yaml, "
                    + "LEGACY_AUDIT_AI_MODEL, or --model.");
        }
        if (s.provider().equals("azure-openai") && (s.baseUrl() == null || s.baseUrl().isEmpty())) {
            throw new AiError("azure-openai needs ai.base_url (https://<resource>.openai.azure.com).");
        }
        this.s = s;
        this.transport = transport != null ? transport : AiClient::post;
        this.env = env;
    }

    public AiSettings settings() {
        return s;
    }

    private String key() {
        String name = s.keyEnv();
        if (s.provider().equals("ollama") && (s.apiKeyEnv() == null || s.apiKeyEnv().isEmpty())) {
            return null;
        }
        String key = name != null ? env.apply(name) : null;
        boolean none = key == null || key.isEmpty();
        if (none && !s.provider().equals("openai")) {
            throw new AiError("Set the environment variable " + name + " to your API key.");
        }
        if (none && s.baseUrl() == null) {
            throw new AiError("Set the environment variable " + name + " to your API key.");
        }
        return none ? null : key;
    }

    /** The model's answer to one system and user prompt. */
    public String complete(String system, String user) {
        return s.provider().equals("anthropic") ? anthropic(system, user) : chat(system, user);
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private String chat(String system, String user) {
        String key = key();
        String url;
        Map<String, String> headers = new LinkedHashMap<>();
        if (s.provider().equals("azure-openai")) {
            url = s.url() + "/openai/deployments/" + s.model() + "/chat/completions?api-version=" + s.apiVersion();
            headers.put("api-key", key == null ? "" : key);
        } else {
            url = s.url() + "/chat/completions";
            if (key != null) {
                headers.put("Authorization", "Bearer " + key);
            }
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("messages", List.of(msg("system", system), msg("user", user)));
            if (!s.provider().equals("azure-openai")) {
                body.put("model", s.model());
            }
            body.put(drop.contains("max_tokens") ? "max_completion_tokens" : "max_tokens", s.maxOutputTokens());
            if (s.temperature() != null && !drop.contains("temperature")) {
                body.put("temperature", s.temperature());
            }
            if (s.jsonMode() && !drop.contains("response_format")) {
                body.put("response_format", Map.of("type", "json_object"));
            }
            Map<String, Object> data;
            try {
                data = transport.post(url, headers, body, s);
            } catch (AiError err) {
                // some models reject a parameter (newer OpenAI models: max_tokens, temperature;
                // some local servers: response_format); drop it and try again
                String text = err.getMessage();
                String bad = null;
                for (String p : List.of("max_tokens", "temperature", "response_format")) {
                    if (text.contains(p) && !drop.contains(p) && text.contains("HTTP 400")) {
                        bad = p;
                        break;
                    }
                }
                if (bad == null) {
                    throw err;
                }
                drop.add(bad);
                continue;
            }
            try {
                Object content = ((Map<?, ?>) ((Map<?, ?>) ((List<?>) data.get("choices")).get(0)).get("message")).get("content");
                return content == null ? "" : Py.str(content);
            } catch (RuntimeException e) {
                throw new AiError("Unexpected answer from " + s.label() + ": " + cut(Py.str(data), 300));
            }
        }
        throw new AiError(s.label() + " kept rejecting the request parameters.");
    }

    private String anthropic(String system, String user) {
        String key = key();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", s.model());
        body.put("system", system);
        body.put("messages", List.of(msg("user", user)));
        body.put("max_tokens", s.maxOutputTokens());
        if (s.temperature() != null) {
            body.put("temperature", s.temperature());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", key == null ? "" : key);
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        Map<String, Object> data = transport.post(s.url() + "/v1/messages", headers, body, s);
        try {
            StringBuilder out = new StringBuilder();
            for (Object b : (List<?>) data.get("content")) {
                Map<?, ?> block = (Map<?, ?>) b;
                if ("text".equals(block.get("type"))) {
                    Object t = block.get("text");
                    out.append(t == null ? "" : Py.str(t));
                }
            }
            return out.toString();
        } catch (RuntimeException e) {
            throw new AiError("Unexpected answer from " + s.label() + ": " + cut(Py.str(data), 300));
        }
    }

    static String cut(String text, int n) {
        return text.length() <= n ? text : text.substring(0, n);
    }

    private static String host(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    /** The real transport: one POST with the JDK HTTP client. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> post(String url, Map<String, String> headers, Map<String, Object> body, AiSettings s) {
        HttpResponse<String> resp;
        try {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(s.timeoutSeconds()))
                    .proxy(proxySelector(System::getenv));
            if (s.caBundle() != null && !s.caBundle().isEmpty()) {
                b.sslContext(sslContext(Path.of(s.caBundle())));
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(s.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            headers.forEach(req::header);
            try (HttpClient client = b.build()) {
                resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiError("Cannot reach " + host(url) + ": interrupted");
        } catch (IOException | IllegalArgumentException e) {
            throw new AiError("Cannot reach " + host(url) + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        if (resp.statusCode() >= 400) {
            throw new AiError("HTTP " + resp.statusCode() + " from " + host(url) + ": " + cut(resp.body(), 600));
        }
        try {
            Object data = JSON.readValue(resp.body(), Object.class);
            if (data instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (JacksonException e) {
            // below
        }
        throw new AiError(host(url) + " did not answer with JSON");
    }

    /** Trust exactly the certificates of a PEM bundle (Python's {@code cafile}). */
    static SSLContext sslContext(Path pem) throws IOException {
        try (InputStream in = Files.newInputStream(pem)) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                ks.setCertificateEntry("ca" + i++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("ai.ca_bundle " + pem + " is not a PEM certificate bundle: " + e.getMessage());
        }
    }

    /**
     * {@code HTTPS_PROXY} for https and {@code HTTP_PROXY} for http (either case) with {@code
     * NO_PROXY}, as curl and Python read them; without one the JVM's own proxy settings.
     */
    static ProxySelector proxySelector(Function<String, String> env) {
        String https = first(env, "HTTPS_PROXY", "https_proxy");
        String http = first(env, "HTTP_PROXY", "http_proxy");
        if (https == null && http == null) {
            return ProxySelector.getDefault();
        }
        String noProxy = first(env, "NO_PROXY", "no_proxy");
        List<String> skip = new ArrayList<>();
        if (noProxy != null) {
            for (String p : noProxy.split(",")) {
                if (!p.isBlank()) {
                    skip.add(p.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                for (String p : skip) {
                    String d = p.startsWith(".") ? p.substring(1) : p;
                    if (p.equals("*") || h.equals(d) || h.endsWith("." + d)) {
                        return List.of(Proxy.NO_PROXY);
                    }
                }
                String proxy = "https".equalsIgnoreCase(uri.getScheme()) ? https : http;
                if (proxy == null) {
                    ProxySelector dflt = ProxySelector.getDefault();
                    return dflt == null ? List.of(Proxy.NO_PROXY) : dflt.select(uri);
                }
                URI p = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
                int port = p.getPort() > 0 ? p.getPort() : 80;
                return List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(p.getHost(), port)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // nothing to remember
            }
        };
    }

    private static String first(Function<String, String> env, String... names) {
        for (String n : names) {
            String v = env.apply(n);
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    /** The JSON object in a model's answer (tolerates {@code ```json} fences and text around it). */
    public static Object extractJson(String text) {
        String t = text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl >= 0 ? t.substring(nl + 1) : "";
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        try {
            return JSON.readValue(t, Object.class);
        } catch (JacksonException e) {
            // look for the object inside
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiError("The model did not answer with a JSON object.");
        }
        try {
            return JSON.readValue(t.substring(start, end + 1), Object.class);
        } catch (JacksonException e) {
            throw new AiError("The model's JSON is not valid: " + e.getOriginalMessage());
        }
    }
}
