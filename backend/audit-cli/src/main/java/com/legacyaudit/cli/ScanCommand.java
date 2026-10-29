package com.legacyaudit.cli;

import com.legacyaudit.engine.scan.Scanner;
import com.legacyaudit.engine.store.ScanLock;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.model.facts.InputCoverage;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code legacy-audit scan}: scan the configured sources and write a new run. */
@Command(name = "scan", description = "Scan the configured sources and write a new run.")
final class ScanCommand implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, description = "Path to audit.yaml (default: audit.yaml).")
    Path config = Path.of("audit.yaml");

    @Option(names = "--full", description = "Ignore the file cache.")
    boolean full;

    @Option(names = {"-o", "--output-dir"}, description = "Override output_dir.")
    Path outputDir;

    @Option(names = {"-v", "--verbose"}, description = "Print the scan log as it is written.")
    boolean verbose;

    @Override
    public Integer call() throws Exception {
        LoadedConfig loaded;
        Scanner.Result result;
        try {
            loaded = ConfigLoader.load(config);
            result = Scanner.run(loaded, new Scanner.Options(full, outputDir, null, null, verbose ? System.err::println : null));
        } catch (ConfigException | ScanLock.ScanBusy e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        var run = result.run();
        System.out.println("Run " + run.runId + " -> " + result.runDir());
        System.out.println("  files: " + run.filesTotal + ", analyzed: " + run.filesAnalyzed + ", from cache: " + run.filesCached
                + ", components: " + result.components().size());
        if (!run.analyzers.isEmpty()) {
            System.out.println("  analyzers: " + Scanner.analyzerLine(run.analyzers));
        }
        for (InputCoverage c : run.coverage) {
            System.out.println("  " + c.line());
        }
        for (String w : run.warnings) {
            System.out.println("  warning: " + w);
        }
        return 0;
    }
}
