package com.legacyaudit.ai.copilot;

import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.model.AppInfo;
import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Copilot agent, instructions, prompt files and enrichment schemas {@code init} installs into
 * a workspace (the same files as the Python tool's).
 *
 * <p>Copilot's file names changed across releases (custom chat modes became custom agents), so the
 * agent is written as {@code .github/agents/*.agent.md}, {@code .github/chatmodes/*.chatmode.md}
 * or both. Front matter is kept to {@code description} and {@code tools}, every value quoted
 * (decision 173).
 *
 * <p>User edits are kept: {@code .github/legacy-audit/installed.json} records the hash of each file
 * as installed. On a later {@code init} a file still equal to that hash is updated in place; a file
 * the user changed is left alone and the new version is written next to it as {@code <name>.new}.
 */
public final class CopilotFiles {

    public static final List<String> LAYOUTS = List.of("agents", "chatmodes", "both", "none");
    public static final String MANIFEST = ".github/legacy-audit/installed.json";
    public static final String AGENT = "legacy-auditor";
    static final String DESCRIPTION = "Audits this legacy application with legacy-audit: runs the scan, enriches the facts stage "
            + "by stage with evidence, validates, and builds the reports.";
    // Tool-set names of VS Code 1.105-1.106. If Copilot reports an unknown tool, delete the
    // `tools:` line: the agent then gets every tool.
    static final String TOOLS = "['edit', 'search', 'runCommands', 'problems', 'legacy-audit/*']";
    static final Map<String, String> PROMPTS = new LinkedHashMap<>();

    static {
        PROMPTS.put("audit-scan", "Run or resume the legacy-audit scan and summarise its coverage");
        PROMPTS.put("audit-enrich", "Enrich the scan facts: all stages, stale stages, or one stage");
        PROMPTS.put("audit-report", "Validate the enrichment and build the HTML and PDF reports");
        PROMPTS.put("audit-qa", "Cross-check the enrichment against the facts and list contradictions");
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One installed file and what happened: created, updated, unchanged or kept (see {@code .new}). */
    public record Installed(String path, String action) {}

    private CopilotFiles() {}

    /** A resource of {@code legacyaudit/ai/}, as text. */
    public static String asset(String name) {
        try (InputStream in = CopilotFiles.class.getResourceAsStream("/legacyaudit/ai/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A front-matter value as a double-quoted YAML string (JSON strings are valid YAML). */
    static String q(String value) {
        return PyJson.compact(value);
    }

    static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Relative path to content for a layout; {@code model} pins the agent's chat model. */
    public static Map<String, String> assets(String layout, String model) {
        return assets(layout, model, null);
    }

    /**
     * The jar this program runs from ({@code java -jar legacy-audit.jar}), or null (tests, IDE).
     */
    public static Path runningJar() {
        String cp = System.getProperty("java.class.path", "");
        if (cp.endsWith(".jar") && !cp.contains(java.io.File.pathSeparator)) {
            Path jar = Path.of(cp).toAbsolutePath().normalize();
            return Files.isRegularFile(jar) ? jar : null;
        }
        return null;
    }

    /**
     * {@code .vscode/mcp.json}. With the jar known, VS Code starts {@code <this java> -jar <jar> mcp}
     * itself (a {@code .cmd} launcher is not started by VS Code on Windows); else the
     * {@code legacy-audit} command on PATH, as the Python tool wrote it.
     */
    static String mcpJson(Path jar) {
        if (jar == null) {
            return asset("copilot/mcp.json");
        }
        return "{\n  \"servers\": {\n    \"legacy-audit\": {\n      \"type\": \"stdio\",\n      \"command\": " + PyJson.compact(javaCommand()) + ",\n"
                + "      \"args\": [\"-jar\", " + PyJson.compact(jar.toString().replace('\\', '/'))
                + ", \"mcp\", \"--config\", \"${workspaceFolder}/audit.yaml\"]\n    }\n  }\n}\n";
    }

    /** The Java that runs this program (forward slashes), else {@code java} from PATH. */
    static String javaCommand() {
        return ProcessHandle.current().info().command().map(c -> c.replace('\\', '/')).orElse("java");
    }

    /** {@link #assets(String, String)}; {@code jar} is the program {@code .vscode/mcp.json} starts. */
    public static Map<String, String> assets(String layout, String model, Path jar) {
        if (!LAYOUTS.contains(layout)) {
            throw new IllegalArgumentException("layout must be one of " + String.join(", ", LAYOUTS));
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (layout.equals("none")) {
            return out;
        }
        String body = asset("copilot/agent.md");
        String pin = model != null && !model.isEmpty() ? "model: " + PyJson.dumps(model, false) + "\n" : "";
        String head = "---\ndescription: " + q(DESCRIPTION) + "\ntools: " + TOOLS + "\n" + pin + "---\n\n";
        if (layout.equals("agents") || layout.equals("both")) {
            out.put(".github/agents/" + AGENT + ".agent.md", head + body);
        }
        if (layout.equals("chatmodes") || layout.equals("both")) {
            out.put(".github/chatmodes/" + AGENT + ".chatmode.md", head + body);
        }
        // prompt files name the agent: `agent:` in current builds, `mode:` in the chat-mode era
        String key = layout.equals("chatmodes") ? "mode" : "agent";
        PROMPTS.forEach((name, desc) -> out.put(".github/prompts/" + name + ".prompt.md",
                "---\n" + key + ": " + AGENT + "\ndescription: " + q(desc) + "\n---\n\n" + asset("copilot/prompts/" + name + ".prompt.md")));
        out.put(".github/copilot-instructions.md", asset("copilot/copilot-instructions.md"));
        out.put(".vscode/mcp.json", mcpJson(jar));
        for (Stages.Stage stage : Stages.STAGES) {
            out.put(".github/legacy-audit/schemas/" + stage.name() + ".schema.json", asset("schemas/" + stage.name() + ".schema.json"));
        }
        return out;
    }

    /** Install (or update) the files in {@code folder}, keeping the user's edits. */
    @SuppressWarnings("unchecked")
    public static List<Installed> install(Path folder, String layout, String model) {
        return install(folder, layout, model, runningJar());
    }

    /** {@link #install(Path, String, String)} with the jar {@code .vscode/mcp.json} starts. */
    public static List<Installed> install(Path folder, String layout, String model, Path jar) {
        Path manifestPath = folder.resolve(MANIFEST);
        Map<String, Object> manifest;
        try {
            manifest = JSON.readValue(Files.readString(manifestPath, StandardCharsets.UTF_8), Map.class);
        } catch (IOException | RuntimeException e) {
            manifest = new LinkedHashMap<>();
        }
        Map<String, Object> recorded = manifest.get("files") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        List<Installed> done = new ArrayList<>();
        try {
            for (Map.Entry<String, String> e : assets(layout, model, jar).entrySet()) {
                String rel = e.getKey();
                String content = e.getValue();
                Path target = folder.resolve(rel);
                String newSha = sha(content);
                String action;
                if (!Files.exists(target)) {
                    action = "created";
                } else {
                    String current = sha(Files.readString(target, StandardCharsets.UTF_8));
                    if (current.equals(newSha)) {
                        recorded.put(rel, newSha);
                        done.add(new Installed(rel, "unchanged"));
                        continue;
                    }
                    if (newSha.equals(recorded.get(rel))) { // edited by the user; no newer version to offer
                        done.add(new Installed(rel, "unchanged"));
                        continue;
                    }
                    if (!current.equals(recorded.get(rel))) { // edited by the user (or not ours)
                        Files.writeString(target.resolveSibling(target.getFileName() + ".new"), content, StandardCharsets.UTF_8);
                        done.add(new Installed(rel, "kept"));
                        continue;
                    }
                    action = "updated";
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, content, StandardCharsets.UTF_8);
                recorded.put(rel, newSha);
                done.add(new Installed(rel, action));
            }
            if (!done.isEmpty()) {
                Files.createDirectories(manifestPath.getParent());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("tool_version", AppInfo.version());
                out.put("files", recorded);
                Files.writeString(manifestPath, PyJson.pretty(out), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return done;
    }
}
