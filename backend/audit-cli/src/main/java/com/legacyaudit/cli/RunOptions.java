package com.legacyaudit.cli;

import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import java.nio.file.Path;
import picocli.CommandLine.Option;

/** The {@code --run}, {@code --config} and {@code --output-dir} options of the run commands. */
final class RunOptions {

    @Option(names = "--run", defaultValue = "latest", description = "Run id, 'latest' or 'previous'.")
    String run;

    @Option(names = {"-c", "--config"}, description = "Path to audit.yaml (default: audit.yaml).")
    Path config = Path.of("audit.yaml");

    @Option(names = {"-o", "--output-dir"}, description = "Override output_dir.")
    Path outputDir;

    /** A message for the user, printed as {@code Error: ...} with exit code 1. */
    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    LoadedConfig load() {
        try {
            return ConfigLoader.load(config);
        } catch (ConfigException e) {
            throw new Failure(e.getMessage());
        }
    }

    Path runDir(LoadedConfig loaded) {
        try {
            return Runs.resolve(outputDir != null ? outputDir : loaded.outputDir(), run);
        } catch (Runs.RunNotFound e) {
            throw new Failure(e.getMessage() + ". Run 'legacy-audit scan' first.");
        }
    }

    Path runDir() {
        return runDir(load());
    }
}
