package com.legacyaudit.cli;

import com.legacyaudit.ai.doctor.Doctor;
import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code legacy-audit doctor}: which tools are there and what a missing one costs. */
@Command(name = "doctor", description = "Check Java, the PDF browser, Dependency-Check, OSV-Scanner, the JDK tools, Git and the AI model.")
final class DoctorCommand implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, description = "audit.yaml (tool paths, output folder).")
    Path config;

    @Override
    public Integer call() {
        AuditConfig cfg = null;
        Path outDir = null;
        Path path = config != null ? config : Path.of("audit.yaml");
        if (Files.exists(path)) {
            try {
                LoadedConfig loaded = ConfigLoader.load(path);
                cfg = loaded.config();
                outDir = loaded.outputDir();
            } catch (ConfigException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        } else if (config != null) {
            System.err.println("Error: " + config + " not found");
            return 1;
        }
        List<Doctor.Check> checks = Doctor.run(cfg, outDir);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"check", "status", "", "detail", "needed for"});
        for (Doctor.Check c : checks) {
            rows.add(new String[] {c.name(), c.status(), c.required() ? "required" : "optional", c.detail(), c.neededFor()});
        }
        Table.print(rows);
        List<String> broken = checks.stream().filter(c -> c.required() && !c.ok()).map(Doctor.Check::name).toList();
        List<String> missing = checks.stream().filter(c -> !c.required() && !c.ok()).map(Doctor.Check::name).toList();
        if (!broken.isEmpty()) {
            System.out.println("Not ready: " + String.join(", ", broken));
            return 1;
        }
        System.out.println("Ready to scan." + (missing.isEmpty() ? "" : " Optional, not available: " + String.join(", ", missing) + "."));
        return 0;
    }
}
