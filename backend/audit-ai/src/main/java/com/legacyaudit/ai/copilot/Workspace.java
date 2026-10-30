package com.legacyaudit.ai.copilot;

import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code legacy-audit init}: the starter files of a legacy workspace and the Copilot files. */
public final class Workspace {

    public static final String CONFIG_FILE = "audit.yaml";
    public static final String OVERRIDES_FILE = "audit-overrides.yaml";

    /** One file or folder and what happened: created, kept, updated or unchanged. */
    public record Created(String path, String action) {}

    private Workspace() {}

    /** {@code audit.yaml} for a new workspace, with the application name filled in. */
    public static String configTemplate(String name) {
        return CopilotFiles.asset("audit.yaml").replace("{name}", PyJson.dumps(name, false)).replace("{{", "{").replace("}}", "}");
    }

    /** Create audit.yaml, audit-overrides.yaml, audit-inputs/<kind>/ and the Copilot files. */
    public static List<Created> init(Path folder, String name, boolean force, String copilotLayout) {
        folder = folder.toAbsolutePath().normalize();
        List<Created> out = new ArrayList<>();
        try {
            Files.createDirectories(folder);
            String appName = name != null && !name.isEmpty() ? name
                    : folder.getFileName() != null ? folder.getFileName().toString() : "application";
            String[][] files = {
                {CONFIG_FILE, configTemplate(appName)},
                {OVERRIDES_FILE, CopilotFiles.asset("audit-overrides.yaml")},
            };
            for (String[] f : files) {
                Path target = folder.resolve(f[0]);
                if (Files.exists(target) && !force) {
                    out.add(new Created(f[0], "kept"));
                    continue;
                }
                Files.writeString(target, f[1], StandardCharsets.UTF_8);
                out.add(new Created(f[0], "created"));
            }
            for (String kind : AuditConfig.INPUT_KINDS.keySet()) {
                Path d = folder.resolve("audit-inputs").resolve(kind);
                if (!Files.exists(d)) {
                    Files.createDirectories(d);
                    Files.writeString(d.resolve(".gitkeep"), "");
                    out.add(new Created("audit-inputs/" + kind + "/", "created"));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (CopilotFiles.Installed i : CopilotFiles.install(folder, copilotLayout, copilotModel(folder))) {
            out.add(new Created(i.path(), i.action()));
        }
        return out;
    }

    /** {@code ai.copilot_model} from the workspace's audit.yaml, if it loads. */
    static String copilotModel(Path folder) {
        try {
            Object m = ConfigLoader.load(folder.resolve(CONFIG_FILE)).config().setting("ai", "copilot_model");
            return m == null ? null : m.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
