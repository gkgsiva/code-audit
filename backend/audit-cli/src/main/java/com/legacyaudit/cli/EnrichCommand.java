package com.legacyaudit.cli;

import com.legacyaudit.ai.model.AiClient;
import com.legacyaudit.ai.model.AiError;
import com.legacyaudit.ai.model.AiSettings;
import com.legacyaudit.ai.model.Enricher;
import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.model.config.LoadedConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code legacy-audit enrich}: write the AI enrichment with any model (OpenAI-compatible,
 * Anthropic, Azure OpenAI, Ollama). Copilot in VS Code stays the default ({@code /audit-enrich});
 * this runs the same stages from the command line with the model set in audit.yaml ({@code ai:})
 * or {@code LEGACY_AUDIT_AI_*} variables. The API key is read from an environment variable.
 */
@Command(name = "enrich", description = "Write the AI enrichment with any model (OpenAI-compatible, Anthropic, Azure, Ollama).")
final class EnrichCommand implements Callable<Integer> {

    @Option(names = {"-s", "--stage"}, description = "Only these stages (repeatable).")
    List<String> stages;

    @Option(names = "--stale", description = "Only the stages that are missing or stale.")
    boolean stale;

    @Option(names = "--provider", description = "copilot, openai, anthropic, azure-openai or ollama (else ai.provider).")
    String provider;

    @Option(names = "--model", description = "Model name (else ai.model).")
    String model;

    @Option(names = "--base-url", description = "Endpoint URL.")
    String baseUrl;

    @Option(names = "--check", description = "Only test the model settings with a tiny request.")
    boolean check;

    @Mixin
    RunOptions opts;

    @Override
    public Integer call() {
        try {
            LoadedConfig loaded = opts.load();
            AiSettings s = AiSettings.of(loaded.config(), provider, model, baseUrl);
            AiClient client = new AiClient(s); // fails early on copilot, a missing model or key settings
            if (check) {
                System.out.println(Enricher.check(client));
                return 0;
            }
            Path runDir = opts.runDir(loaded);
            List<Stages.Stage> todo = Enricher.pickStages(runDir, stages, stale);
            if (todo.isEmpty()) {
                System.out.println("Nothing to do: every stage is present and up to date.");
                return 0;
            }
            System.out.println("Enriching " + runDir.getFileName() + " with " + s.label() + ": " + todo.size() + " stage(s).");
            List<Enricher.StageResult> results = Enricher.enrich(runDir, todo, client, System.out::println);
            for (Enricher.StageResult res : results) {
                res.errors.stream().limit(10).forEach(e -> System.err.println(res.stage + ": " + e));
            }
            List<String> failed = results.stream().filter(r -> !r.ok).map(r -> r.stage).toList();
            System.out.println((results.size() - failed.size()) + " stage(s) valid, " + failed.size() + " with errors"
                    + (failed.isEmpty() ? "" : " (" + String.join(", ", failed) + ")")
                    + ". Build the reports with 'legacy-audit report --type all'.");
            return failed.isEmpty() ? 0 : 1;
        } catch (AiError | RunOptions.Failure e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
