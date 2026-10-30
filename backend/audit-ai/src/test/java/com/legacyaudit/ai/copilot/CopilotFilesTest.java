package com.legacyaudit.ai.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.json.PyJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** {@code init}: the Copilot files, their layouts, and keeping the user's edits on a re-run. */
class CopilotFilesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Changed on purpose from the Python tool: no PowerPoint, PDF copies instead (decision 229). */
    private static final Set<String> CHANGED = Set.of(".github/prompts/audit-report.prompt.md");

    private static Map<String, String> actions(List<CopilotFiles.Installed> done) {
        Map<String, String> out = new LinkedHashMap<>();
        done.forEach(d -> out.put(d.path(), d.action()));
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void filesAreThePythonToolsFiles() throws Exception {
        Map<String, Map<String, String>> expected = JSON.readValue(
                Files.readString(Path.of("src", "test", "resources", "copilot-parity", "expected.json")), Map.class);
        for (Map.Entry<String, Map<String, String>> e : expected.entrySet()) {
            String[] key = e.getKey().split("\\|", -1);
            Map<String, String> got = CopilotFiles.assets(key[0], key[1].isEmpty() ? null : key[1]);
            assertEquals(e.getValue().keySet(), got.keySet(), e.getKey());
            for (String rel : got.keySet()) {
                if (!CHANGED.contains(rel)) {
                    assertEquals(e.getValue().get(rel), CopilotFiles.sha(got.get(rel)), e.getKey() + ": " + rel);
                }
            }
        }
        String report = CopilotFiles.assets("agents", null).get(".github/prompts/audit-report.prompt.md");
        assertTrue(report.contains("PDF") && !report.contains("pptx"), report);
    }

    @Test
    void initInstallsTheFiles(@TempDir Path dir) throws Exception {
        List<Workspace.Created> done = Workspace.init(dir, "Billing \"app\"", false, "both");
        assertTrue(done.stream().anyMatch(d -> d.path().equals("audit.yaml") && d.action().equals("created")));
        assertEquals("Billing \"app\"", ConfigLoader.load(dir.resolve("audit.yaml")).config().application().name());
        assertTrue(Files.isRegularFile(dir.resolve("audit-overrides.yaml")));
        assertTrue(Files.isRegularFile(dir.resolve("audit-inputs/autosys/.gitkeep")));
        Path gh = dir.resolve(".github");
        for (String rel : List.of("agents/legacy-auditor.agent.md", "chatmodes/legacy-auditor.chatmode.md", "copilot-instructions.md",
                "prompts/audit-scan.prompt.md", "prompts/audit-enrich.prompt.md", "prompts/audit-report.prompt.md",
                "prompts/audit-qa.prompt.md", "legacy-audit/schemas/plan.schema.json")) {
            assertTrue(Files.isRegularFile(gh.resolve(rel)), rel);
        }
        Map<?, ?> mcp = JSON.readValue(Files.readString(dir.resolve(".vscode/mcp.json")), Map.class);
        assertTrue(((Map<?, ?>) mcp.get("servers")).containsKey("legacy-audit"));
        String agent = Files.readString(gh.resolve("agents/legacy-auditor.agent.md"));
        assertTrue(agent.startsWith("---\ndescription: ") && agent.contains("\ntools: "));
        assertTrue(agent.contains("Numbers come only from the scanner"));
        String enrich = Files.readString(gh.resolve("prompts/audit-enrich.prompt.md"));
        assertTrue(enrich.startsWith("---\nagent: legacy-auditor\n"));
        for (Stages.Stage st : Stages.STAGES) {
            assertTrue(enrich.contains("`" + st.name() + "`"), st.name());
        }
        // a second init keeps audit.yaml and changes nothing
        List<Workspace.Created> again = Workspace.init(dir, null, false, "both");
        assertTrue(again.stream().anyMatch(d -> d.path().equals("audit.yaml") && d.action().equals("kept")));
        assertTrue(again.stream().filter(d -> d.path().startsWith(".")).allMatch(d -> d.action().equals("unchanged")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reinitKeepsUserEdits(@TempDir Path dir) throws Exception {
        assertEquals(Set.of("created"), Set.copyOf(actions(CopilotFiles.install(dir, "both", null, null)).values()));
        Path instr = dir.resolve(".github/copilot-instructions.md");
        Files.writeString(instr, Files.readString(instr) + "\n- Our team rule.\n");
        Map<String, String> second = actions(CopilotFiles.install(dir, "both", null, null));
        assertEquals("unchanged", second.get(".github/copilot-instructions.md")); // edited: left alone
        assertEquals("unchanged", second.get(".github/agents/legacy-auditor.agent.md"));
        assertTrue(Files.readString(instr).contains("Our team rule."));
        assertFalse(Files.exists(instr.resolveSibling("copilot-instructions.md.new")));

        // a new tool version changes the template: unedited files update, edited ones are kept
        Map<String, Object> manifest = JSON.readValue(Files.readString(dir.resolve(CopilotFiles.MANIFEST)), Map.class);
        Path agent = dir.resolve(".github/agents/legacy-auditor.agent.md");
        Files.writeString(agent, "old version\n");
        String instrOld = "old instructions\n";
        Files.writeString(instr, instrOld + "- Our team rule.\n");
        Map<String, Object> files = (Map<String, Object>) manifest.get("files");
        files.put(".github/agents/legacy-auditor.agent.md", CopilotFiles.sha("old version\n"));
        files.put(".github/copilot-instructions.md", CopilotFiles.sha(instrOld));
        Files.writeString(dir.resolve(CopilotFiles.MANIFEST), PyJson.pretty(manifest));
        Map<String, String> third = actions(CopilotFiles.install(dir, "both", null, null));
        assertEquals("updated", third.get(".github/agents/legacy-auditor.agent.md"));
        assertEquals(CopilotFiles.assets("both", null).get(".github/agents/legacy-auditor.agent.md"), Files.readString(agent));
        assertEquals("kept", third.get(".github/copilot-instructions.md"));
        assertTrue(Files.readString(instr).contains("Our team rule."));
        assertEquals(CopilotFiles.assets("both", null).get(".github/copilot-instructions.md"),
                Files.readString(instr.resolveSibling("copilot-instructions.md.new")));
    }

    @Test
    void layoutsAndModelPin(@TempDir Path dir) {
        Map<String, String> agents = CopilotFiles.assets("agents", null);
        assertTrue(agents.containsKey(".github/agents/legacy-auditor.agent.md"));
        assertTrue(agents.keySet().stream().noneMatch(p -> p.contains("chatmodes")));
        Map<String, String> chat = CopilotFiles.assets("chatmodes", null);
        assertTrue(chat.get(".github/prompts/audit-scan.prompt.md").startsWith("---\nmode: legacy-auditor\n"));
        assertTrue(CopilotFiles.assets("none", null).isEmpty());
        String pinned = CopilotFiles.assets("agents", "Claude Sonnet 4.5 (copilot)").get(".github/agents/legacy-auditor.agent.md");
        assertTrue(pinned.contains("model: \"Claude Sonnet 4.5 (copilot)\"\n---"));
        assertFalse(agents.get(".github/agents/legacy-auditor.agent.md").split("---")[1].contains("model:"));
    }

    @Test
    void mcpJsonStartsTheJarWhenKnown(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("legacy-audit.jar");
        Map<?, ?> mcp = JSON.readValue(CopilotFiles.assets("agents", null, jar).get(".vscode/mcp.json"), Map.class);
        Map<?, ?> server = (Map<?, ?>) ((Map<?, ?>) mcp.get("servers")).get("legacy-audit");
        assertEquals("stdio", server.get("type"));
        assertEquals(List.of("-jar", jar.toString().replace('\\', '/'), "mcp", "--config", "${workspaceFolder}/audit.yaml"), server.get("args"));
    }
}
