package com.legacyaudit.ai.doctor;

import com.legacyaudit.ai.model.AiError;
import com.legacyaudit.ai.model.AiSettings;
import com.legacyaudit.model.AppInfo;
import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.ConfigReader;
import com.legacyaudit.engine.security.Jdk;
import com.legacyaudit.engine.security.Vulns;
import com.legacyaudit.reports.PdfReports;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code legacy-audit doctor} and the tool checks of the settings and health pages: what is
 * installed, what is optional and what each missing tool costs ({@code doctor.py} of the Python
 * tool). Only Java and a writable output folder are required; the rest adds an output (PDF
 * copies, vulnerability data, JDK checks, Git sources, AI enrichment) and its absence is
 * reported, never fatal. The PowerPoint checks (Node.js, Mermaid CLI, templates) are gone with
 * the PowerPoint.
 */
public final class Doctor {

    public static final int MIN_JAVA = 25;

    /** One line of the report: {@code status} is ok, missing, old or error. */
    public record Check(String name, String status, boolean required, String detail, String neededFor) {

        public boolean ok() {
            return status.equals("ok");
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("status", status);
            m.put("required", required);
            m.put("detail", detail);
            m.put("needed_for", neededFor);
            return m;
        }
    }

    private Doctor() {}

    /** Every check for this configuration (null: the defaults) and output folder (null: skipped). */
    public static List<Check> run(AuditConfig cfg, Path outputDir) {
        return run(cfg, outputDir, System::getenv);
    }

    public static List<Check> run(AuditConfig cfg, Path outputDir, Function<String, String> env) {
        if (cfg == null) {
            cfg = ConfigReader.parse(Map.of("application", Map.of("name", "doctor")), "doctor");
        }
        List<Check> out = new ArrayList<>();
        out.add(java());
        if (outputDir != null) {
            out.add(writable(outputDir));
        }
        out.add(browser(str(section(cfg, "pdf").get("browser_path"))));
        Map<String, Object> dc = section(section(cfg, "security"), "dependency_check");
        List<String> dcNames = List.of("dependency-check", "dependency-check.sh", "dependency-check.bat");
        String dcPath = str(dc.get("path"));
        String dcExe = Vulns.findTool(dcPath, dcNames);
        Check dcCheck = tool("OWASP Dependency-Check", dcExe,
                "known vulnerabilities in libraries (or import its JSON into audit-inputs/)",
                Vulns.missingDetail(dcPath, dcNames) + "; vulnerability data only from imported reports");
        String dataDir = str(dc.get("data_dir"));
        if (dcExe != null && dataDir != null && !dataDir.isEmpty() && !Files.isDirectory(Path.of(dataDir))) {
            dcCheck = new Check(dcCheck.name(), "error", false, "data_dir " + dataDir + " does not exist", dcCheck.neededFor());
        }
        out.add(dcCheck);
        List<String> osvNames = List.of("osv-scanner", "osv-scanner.exe");
        String osvPath = str(section(section(cfg, "security"), "osv_scanner").get("path"));
        out.add(tool("OSV-Scanner", Vulns.findTool(osvPath, osvNames), "known vulnerabilities in libraries",
                Vulns.missingDetail(osvPath, osvNames)));
        String javaHome = str(section(section(cfg, "security"), "jdk_tools").get("java_home"));
        out.add(tool("JDK tools (jdeps)", Jdk.findTool(javaHome, "jdeps"),
                "Java 25 internal-API checks on jars (security.jdk_tools.enabled)",
                "not found" + (javaHome != null && !javaHome.isEmpty() ? " in " + javaHome : "")));
        String git = Vulns.which("git");
        out.add(new Check("Git", git != null ? "ok" : "missing", cfg.source() instanceof AuditConfig.GitSource,
                git != null ? git : "not on PATH", "Git sources (source.type: git)"));
        out.add(ai(cfg, env));
        return out;
    }

    static Check java() {
        int feature = Runtime.version().feature();
        String home = System.getProperty("java.home");
        return new Check("Java", feature >= MIN_JAVA ? "ok" : "old", true,
                Runtime.version() + " at " + home + " (legacy-audit " + AppInfo.version() + ")", "everything (" + MIN_JAVA + " or later)");
    }

    static Check writable(Path path) {
        Path target = path.toAbsolutePath();
        while (!Files.exists(target) && target.getParent() != null) {
            target = target.getParent();
        }
        Path probe = target.resolve(".legacy-audit-write-test");
        String status;
        String detail;
        try {
            Files.writeString(probe, "x");
            Files.delete(probe);
            status = "ok";
            detail = path.toString();
        } catch (IOException e) {
            status = "error";
            detail = path + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return new Check("Output folder", status, true, detail, "writing runs and reports");
    }

    static Check browser(String configured) {
        PdfReports.Browser b = PdfReports.findBrowser(configured);
        String needed = "PDF copies of the reports (legacy-audit report --type pdf)";
        if (b == null) {
            String detail = PdfReports.missingMessage(configured).replaceFirst("^PDF skipped: ", "");
            return new Check("Browser for PDFs", "missing", false, detail, needed);
        }
        String where = b.windowsFromWsl() ? " (Windows, started from WSL)" : "";
        return new Check("Browser for PDFs", "ok", false, b.label() + " at " + b.path() + where, needed);
    }

    static Check tool(String name, String exe, String neededFor, String hint) {
        return new Check(name, exe != null ? "ok" : "missing", false, exe != null ? exe : hint, neededFor);
    }

    /** The model {@code legacy-audit enrich} would use; the key's variable is checked, not its value. */
    static Check ai(AuditConfig cfg, Function<String, String> env) {
        String name = "AI model";
        String needed = "AI enrichment outside Copilot (legacy-audit enrich)";
        AiSettings s;
        try {
            s = AiSettings.of(cfg, env, null, null, null, null);
        } catch (AiError e) {
            return new Check(name, "error", false, e.getMessage(), needed);
        }
        if (s.provider().equals("copilot")) {
            String pin = str(section(cfg, "ai").get("copilot_model"));
            return new Check(name, "ok", false, "Copilot in VS Code" + (pin != null && !pin.isEmpty() ? " (" + pin + ")" : ""), needed);
        }
        if (s.model() == null || s.model().isEmpty()) {
            return new Check(name, "missing", false, s.provider() + ": set ai.model", needed);
        }
        String keyEnv = s.keyEnv();
        boolean keyless = s.provider().equals("ollama")
                || (s.provider().equals("openai") && s.baseUrl() != null && !s.baseUrl().isEmpty());
        boolean ownKeyEnv = s.apiKeyEnv() != null && !s.apiKeyEnv().isEmpty();
        String value = keyEnv == null ? null : env.apply(keyEnv);
        if (keyEnv != null && (value == null || value.isEmpty()) && !(keyless && !ownKeyEnv)) {
            return new Check(name, "missing", false, s.label() + ": environment variable " + keyEnv + " not set", needed);
        }
        return new Check(name, "ok", false, s.label() + " at " + s.url() + " (test: legacy-audit enrich --check)", needed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(AuditConfig cfg, String key) {
        return cfg.dump().get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        return parent.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
