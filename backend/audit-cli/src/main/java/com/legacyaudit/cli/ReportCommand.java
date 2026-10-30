package com.legacyaudit.cli;

import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.reports.D;
import com.legacyaudit.reports.FunctionalReport;
import com.legacyaudit.reports.MgmtReport;
import com.legacyaudit.reports.PdfReports;
import com.legacyaudit.reports.SummaryReport;
import com.legacyaudit.reports.TechReport;
import java.awt.Desktop;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code legacy-audit report}: build reports from a run (default: the latest) into {@code <run>/reports/}. */
@Command(name = "report", description = "Build reports from a run (default: the latest) into <run>/reports/.")
final class ReportCommand implements Callable<Integer> {

    static final List<String> TYPES = List.of("tech", "functional", "mgmt", "summary", "pdf", "all");

    @Option(names = {"-t", "--type"}, defaultValue = "tech", description = "tech, functional, mgmt, summary, pdf or all.")
    String type;

    @Option(names = "--run", defaultValue = "latest", description = "Run id, 'latest' or 'previous'.")
    String run;

    @Option(names = {"-c", "--config"}, description = "Path to audit.yaml (default: audit.yaml).")
    Path config = Path.of("audit.yaml");

    @Option(names = {"-o", "--output-dir"}, description = "Override output_dir.")
    Path outputDir;

    @Option(names = "--open", description = "Open the report in the browser.")
    boolean open;

    @Override
    @SuppressWarnings("unchecked")
    public Integer call() {
        if (!TYPES.contains(type)) {
            System.err.println("Error: Unknown report type '" + type + "' (use " + String.join(", ", TYPES) + ")");
            return 1;
        }
        LoadedConfig loaded;
        Path runDir;
        try {
            loaded = ConfigLoader.load(config);
        } catch (ConfigException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        try {
            runDir = Runs.resolve(outputDir != null ? outputDir : loaded.outputDir(), run);
        } catch (Runs.RunNotFound e) {
            System.err.println("Error: " + e.getMessage() + ". Run 'legacy-audit scan' first.");
            return 1;
        }
        Map<String, Object> dump = loaded.config().dump();
        Map<String, Object> branding = dump.get("branding") instanceof Map<?, ?> b ? (Map<String, Object>) b : null;
        Path configDir = loaded.path().toAbsolutePath().getParent();
        Path toOpen = null;
        boolean all = type.equals("all") || type.equals("pdf");
        if (type.equals("tech") || all) {
            toOpen = TechReport.build(runDir, loaded.inputsDir());
            System.out.println("Technical report -> " + toOpen);
        }
        if (type.equals("functional") || all) {
            Path index = FunctionalReport.build(runDir, loaded.inputsDir());
            System.out.println("Functional document -> " + index);
            toOpen = toOpen != null ? toOpen : index;
        }
        if (type.equals("mgmt") || all) {
            Path deck = MgmtReport.build(runDir, branding, configDir);
            System.out.println("Management deck -> " + deck);
            toOpen = toOpen != null ? toOpen : deck;
        }
        if (type.equals("summary") || all) {
            Path page = SummaryReport.build(runDir, Set.of());
            System.out.println("Summary report -> " + page);
            toOpen = toOpen != null ? toOpen : page;
        }
        PdfReports.PdfConfig pdf = PdfReports.PdfConfig.of(dump.get("pdf"));
        if (type.equals("pdf") || (type.equals("all") && pdf.enabled())) {
            System.out.println("Printing the PDF copies (this takes a minute)...");
            PdfReports.PdfResult res = PdfReports.build(runDir, pdf);
            res.files.forEach((key, path) ->
                    System.out.println("PDF " + PdfReports.REPORTS.get(key).kind().toLowerCase(java.util.Locale.ROOT) + " -> " + path));
            if (res.browser != null && !res.files.isEmpty()) {
                System.out.println("  Printed with " + res.browser.label() + " (" + res.browser.path() + ").");
            }
            res.messages.forEach(System.out::println);
            if (type.equals("pdf") && res.files.isEmpty()) {
                return 1;
            }
        }
        if (!type.equals("tech")) {
            Map<String, Object> status = Status.compute(runDir);
            int missing = D.list(status, "missing").size();
            int stale = D.list(status, "stale").size();
            if (missing > 0 || stale > 0) {
                System.out.println("  Enrichment: " + missing + " stage(s) missing, " + stale + " stale; their slides show a "
                        + "placeholder or a warning (run /audit-enrich in Copilot, then legacy-audit validate).");
            }
        }
        if (open && toOpen != null) {
            try {
                Desktop.getDesktop().browse(toOpen.toAbsolutePath().toUri());
            } catch (Exception e) {
                System.out.println("Open " + toOpen.toAbsolutePath().toUri() + " in a browser.");
            }
        }
        return 0;
    }
}
