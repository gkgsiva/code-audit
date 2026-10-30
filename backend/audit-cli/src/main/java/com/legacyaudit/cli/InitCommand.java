package com.legacyaudit.cli;

import com.legacyaudit.ai.copilot.CopilotFiles;
import com.legacyaudit.ai.copilot.Workspace;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code legacy-audit init}: starter files and the Copilot agent files of a legacy workspace. */
@Command(name = "init", description = "Create audit.yaml, audit-overrides.yaml, audit-inputs/ and the Copilot agent files.")
final class InitCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Legacy workspace folder (default: this folder).")
    Path folder;

    @Option(names = "--name", description = "Application name (default: the folder name).")
    String name;

    @Option(names = "--force", description = "Overwrite existing starter files.")
    boolean force;

    @Option(names = "--copilot-layout", defaultValue = "both",
            description = "Copilot agent files: agents (.agent.md), chatmodes (older VS Code), both, none.")
    String copilotLayout;

    @Override
    public Integer call() {
        if (!CopilotFiles.LAYOUTS.contains(copilotLayout)) {
            System.err.println("Error: --copilot-layout must be one of " + String.join(", ", CopilotFiles.LAYOUTS));
            return 1;
        }
        List<Workspace.Created> done = Workspace.init(folder, name, force, copilotLayout);
        for (Workspace.Created item : done) {
            if (item.action().equals("created") || item.action().equals("updated")) {
                System.out.println(item.action() + " " + item.path());
            }
        }
        for (Workspace.Created item : done) {
            if (!item.action().equals("kept")) {
                continue;
            }
            if (item.path().equals(Workspace.CONFIG_FILE) || item.path().equals(Workspace.OVERRIDES_FILE)) {
                System.out.println("Kept existing " + item.path() + " (use --force to overwrite)");
            } else {
                System.out.println("kept your edits " + item.path() + " (new version written to " + item.path() + ".new)");
            }
        }
        System.out.println("Next: review audit.yaml, then run legacy-audit scan, or in VS Code open "
                + "Copilot Chat, pick the legacy-auditor agent and run /audit-scan.");
        return 0;
    }
}
