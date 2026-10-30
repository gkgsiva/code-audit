package com.legacyaudit.cli;

import com.legacyaudit.ai.mcp.Facts;
import com.legacyaudit.ai.mcp.McpServer;
import com.legacyaudit.engine.store.FactsQuery.FactsError;
import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code legacy-audit mcp}: the latest run's facts for Copilot, as a read-only MCP server (stdio). */
@Command(name = "mcp", description = "Serve the latest run's facts to Copilot as a read-only MCP server (stdio).")
final class McpCommand implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, description = "Path to audit.yaml (default: audit.yaml).")
    Path config = Path.of("audit.yaml");

    @Option(names = {"-o", "--output-dir"}, description = "Override output_dir.")
    Path outputDir;

    @Override
    public Integer call() {
        LoadedConfig loaded;
        try {
            loaded = ConfigLoader.load(config);
        } catch (ConfigException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        Path out = outputDir != null ? outputDir : loaded.outputDir();
        Facts facts = new Facts(() -> {
            try {
                return Runs.resolve(out, "latest");
            } catch (Runs.RunNotFound e) {
                throw new FactsError(e.getMessage() + ". Run 'legacy-audit scan' first.");
            }
        });
        // UTF-8 both ways, whatever the console code page (Windows)
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream stdout = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        new McpServer(facts).serve(in, stdout);
        return 0;
    }
}
