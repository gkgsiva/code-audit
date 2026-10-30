package com.legacyaudit.cli;

import com.legacyaudit.engine.proof.Proofs;
import com.legacyaudit.model.AppInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Entry point of {@code java -jar legacy-audit.jar}. Commands are added phase by phase. */
@Command(
        name = AppInfo.NAME,
        mixinStandardHelpOptions = true,
        versionProvider = Main.Version.class,
        description = "Audit legacy applications before migrating them.",
        subcommands = {Main.VersionCommand.class, InitCommand.class, ScanCommand.class, ReportCommand.class, ValidateCommand.class,
            EnrichCommand.class, FactsCommand.class, McpCommand.class, DiffCommand.class, Main.ProofsCommand.class})
public final class Main implements Runnable {

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String... args) {
        return new CommandLine(new Main()).execute(args);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {AppInfo.NAME + " " + AppInfo.version()};
        }
    }

    @Command(name = "version", description = "Print the version.")
    static final class VersionCommand implements Runnable {
        @Override
        public void run() {
            System.out.println(AppInfo.NAME + " " + AppInfo.version());
        }
    }

    @Command(name = "proofs", description = "Run the Phase 0 parser proofs on the sample applications.")
    static final class ProofsCommand implements Callable<Integer> {

        @Option(names = "--samples", description = "Folder with the sample applications (default: samples).")
        Path samples = Path.of("samples");

        @Option(names = "--out", description = "Write the report to this Markdown file instead of the screen.")
        Path out;

        @Override
        public Integer call() throws IOException {
            if (!Files.isDirectory(samples)) {
                System.err.println("Samples folder not found: " + samples.toAbsolutePath());
                return 2;
            }
            String md = Proofs.markdown(Proofs.run(samples));
            if (out == null) {
                System.out.print(md);
            } else {
                Files.writeString(out, md);
                System.out.println("Proof report written to " + out.toAbsolutePath());
            }
            return 0;
        }
    }
}
