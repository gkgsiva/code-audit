package com.legacyaudit.ai.model;

import com.legacyaudit.model.config.AuditConfig;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The {@code ai:} section of audit.yaml after environment and command-line overrides: which model
 * {@code legacy-audit enrich} calls and how. The API key itself is never here: {@link #keyEnv()}
 * names the environment variable that holds it.
 */
public record AiSettings(
        String provider,
        String model,
        String baseUrl,
        String apiKeyEnv,
        String apiVersion,
        Double temperature,
        int maxOutputTokens,
        boolean jsonMode,
        int contextChars,
        int timeoutSeconds,
        int repairRounds,
        String caBundle) {

    public static final String ENV_PREFIX = "LEGACY_AUDIT_AI_";
    public static final List<String> PROVIDERS = List.of("copilot", "openai", "anthropic", "azure-openai", "ollama");
    static final Map<String, String> DEFAULT_URL = Map.of(
            "openai", "https://api.openai.com/v1",
            "ollama", "http://localhost:11434/v1",
            "anthropic", "https://api.anthropic.com");
    static final Map<String, String> DEFAULT_KEY_ENV = Map.of(
            "openai", "OPENAI_API_KEY",
            "anthropic", "ANTHROPIC_API_KEY",
            "azure-openai", "AZURE_OPENAI_API_KEY");

    /** {@code provider:model}, or the provider alone. */
    public String label() {
        return model != null && !model.isEmpty() ? provider + ":" + model : provider;
    }

    /** The endpoint: {@code base_url}, else the provider's default, without a trailing slash. */
    public String url() {
        String u = baseUrl != null && !baseUrl.isEmpty() ? baseUrl : DEFAULT_URL.getOrDefault(provider, "");
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** The environment variable holding the API key (null when the provider has none). */
    public String keyEnv() {
        return apiKeyEnv != null && !apiKeyEnv.isEmpty() ? apiKeyEnv : DEFAULT_KEY_ENV.get(provider);
    }

    /** From audit.yaml, then {@code LEGACY_AUDIT_AI_*} variables, then the command line. */
    public static AiSettings of(AuditConfig cfg, String provider, String model, String baseUrl) {
        return of(cfg, System::getenv, provider, model, baseUrl, null);
    }

    /**
     * {@link #of(AuditConfig, String, String, String)} with the environment given; {@code
     * apiKeyEnv} overrides the variable name.
     */
    public static AiSettings of(AuditConfig cfg, Function<String, String> env, String provider, String model, String baseUrl,
            String apiKeyEnv) {
        Map<?, ?> ai = cfg.dump().get("ai") instanceof Map<?, ?> m ? m : Map.of();
        String[] values = {str(ai.get("provider"), "copilot"), str(ai.get("model"), null), str(ai.get("base_url"), null),
            str(ai.get("api_key_env"), null)};
        String[] names = {"provider", "model", "base_url", "api_key_env"};
        String[] given = {provider, model, baseUrl, apiKeyEnv};
        for (int i = 0; i < names.length; i++) {
            String v = given[i] != null && !given[i].isEmpty() ? given[i] : env.apply(ENV_PREFIX + names[i].toUpperCase(java.util.Locale.ROOT));
            if (v != null && !v.isEmpty()) {
                values[i] = v;
            }
        }
        if (!PROVIDERS.contains(values[0])) {
            throw new AiError("Unknown AI provider '" + values[0] + "'; use one of " + String.join(", ", PROVIDERS) + ".");
        }
        Object temp = ai.get("temperature");
        return new AiSettings(values[0], values[1], values[2], values[3],
                str(ai.get("api_version"), "2024-10-21"),
                ai.containsKey("temperature") ? (temp == null ? null : Double.valueOf(((Number) temp).doubleValue())) : Double.valueOf(0.1),
                num(ai.get("max_output_tokens"), 16000),
                !Boolean.FALSE.equals(ai.get("json_mode")),
                num(ai.get("context_chars"), 150_000),
                num(ai.get("timeout_seconds"), 600),
                num(ai.get("repair_rounds"), 2),
                str(ai.get("ca_bundle"), null));
    }

    private static String str(Object v, String dflt) {
        return v == null ? dflt : v.toString();
    }

    private static int num(Object v, int dflt) {
        return v instanceof Number n ? n.intValue() : dflt;
    }
}
