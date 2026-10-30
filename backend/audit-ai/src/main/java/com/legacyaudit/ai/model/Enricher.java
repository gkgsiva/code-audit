package com.legacyaudit.ai.model;

import com.legacyaudit.ai.enrich.Validator;
import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.reports.RunData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * {@code legacy-audit enrich}: writes the enrichment stages with any model, then validates them.
 *
 * <p>For each stage, in order: build the prompt ({@link PromptContext}), call the model, take the
 * JSON out of its answer, write {@code enrichment/<stage>.json} and run the same validator
 * Copilot's output goes through. When the validator finds errors they are sent back to the model
 * to fix ({@code ai.repair_rounds} times). A stage that still fails keeps its file, marked invalid,
 * and the run goes on. The model only writes words: every number and id it uses is checked
 * against the scan.
 */
public final class Enricher {

    private static final Logger LOG = Logger.getLogger(Enricher.class.getName());

    /** What happened to one stage. */
    public static final class StageResult {
        public final String stage;
        public boolean ok;
        public int rounds;
        public List<String> errors = new ArrayList<>();
        public int warnings;

        StageResult(String stage) {
            this.stage = stage;
        }
    }

    private Enricher() {}

    /** Stages to run, in enrichment order: the named ones, the stale and missing ones, or all. */
    public static List<Stages.Stage> pickStages(Path runDir, List<String> names, boolean stale) {
        List<Stages.Stage> active = Status.activeStages(runDir);
        if (names != null && !names.isEmpty()) {
            Set<String> unknown = new TreeSet<>(names);
            unknown.removeAll(Stages.BY_NAME.keySet());
            if (!unknown.isEmpty()) {
                throw new AiError("No such stage: " + String.join(", ", unknown));
            }
            Set<String> skipped = new TreeSet<>(names);
            active.forEach(s -> skipped.remove(s.name()));
            if (!skipped.isEmpty()) {
                throw new AiError("Not needed when the target is none (audit only): " + String.join(", ", skipped));
            }
            return active.stream().filter(s -> names.contains(s.name())).toList();
        }
        if (stale) {
            Map<?, ?> status = (Map<?, ?>) Status.compute(runDir).get("stages");
            return active.stream().filter(s -> {
                Map<?, ?> e = (Map<?, ?>) status.get(s.name());
                return !Boolean.TRUE.equals(e.get("present")) || Boolean.TRUE.equals(e.get("stale"));
            }).toList();
        }
        return active;
    }

    private static void write(Path runDir, Stages.Stage stage, Map<String, Object> data) {
        for (Path root : new Path[] {runDir, Status.latestCopy(runDir)}) {
            if (root != null) {
                try {
                    Files.createDirectories(root.resolve("enrichment"));
                    Files.writeString(root.resolve("enrichment").resolve(stage.file()), PyJson.pretty(data), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    /** Ask the model for one stage, validate, and send errors back until it passes or rounds run out. */
    @SuppressWarnings("unchecked")
    public static StageResult runStage(Path runDir, Stages.Stage stage, AiClient client, RunData rd) {
        AiSettings s = client.settings();
        PromptContext.Prompts p = PromptContext.build(runDir, stage, s.contextChars(), rd);
        StageResult result = new StageResult(stage.name());
        String prompt = p.user();
        for (int attempt = 0; attempt <= s.repairRounds(); attempt++) {
            result.rounds = attempt + 1;
            String answer = client.complete(p.system(), prompt);
            Object data;
            try {
                data = AiClient.extractJson(answer);
            } catch (AiError err) {
                result.errors = List.of(err.getMessage());
                prompt = p.user() + "\n\n" + PromptContext.repairPrompt(PromptContext.head(answer, 4000), result.errors);
                continue;
            }
            if (!(data instanceof Map<?, ?>)) {
                result.errors = List.of("the answer is not a JSON object");
                prompt = p.user() + "\n\n" + PromptContext.repairPrompt(PromptContext.head(answer, 4000), result.errors);
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) data;
            Map<String, Object> meta = new LinkedHashMap<>();
            if (map.get("meta") instanceof Map<?, ?> raw) {
                raw.forEach((k, v) -> {
                    if (!"built_from".equals(k)) {
                        meta.put(String.valueOf(k), v);
                    }
                });
            }
            meta.put("stage", stage.name());
            meta.put("author", s.label());
            map.put("meta", meta);
            write(runDir, stage, map);
            Validator.Report rep = Validator.validate(runDir, List.of(stage.name()), true);
            result.errors = rep.errors().stream().map(Object::toString).toList();
            result.warnings = rep.warnings().size();
            if (result.errors.isEmpty()) {
                result.ok = true;
                return result;
            }
            LOG.info(stage.name() + ": " + result.errors.size() + " error(s), asking " + s.label() + " to fix them");
            prompt = p.user() + "\n\n" + PromptContext.repairPrompt(answer, result.errors);
        }
        return result;
    }

    /** Run the stages in order; stop when the endpoint itself fails (later stages would too). */
    public static List<StageResult> enrich(Path runDir, List<Stages.Stage> stages, AiClient client, Consumer<String> progress) {
        AiSettings s = client.settings();
        Status.syncFromLatest(runDir);
        RunData rd = stages.stream().anyMatch(x -> PromptContext.SOURCE_STAGES.contains(x.name())) ? RunData.load(runDir) : null;
        List<StageResult> results = new ArrayList<>();
        for (Stages.Stage stage : stages) {
            progress.accept("Stage " + stage.number() + " " + stage.name() + ": asking " + s.label() + "...");
            StageResult res;
            try {
                res = runStage(runDir, stage, client, rd);
            } catch (AiError err) {
                res = new StageResult(stage.name());
                res.errors = List.of(err.getMessage());
                results.add(res);
                progress.accept("Stage " + stage.number() + " " + stage.name() + ": stopped: " + err.getMessage());
                break;
            }
            results.add(res);
            String state = res.ok ? "valid" : res.errors.size() + " error(s) left";
            progress.accept("Stage " + stage.number() + " " + stage.name() + ": " + state + " after " + res.rounds + " round(s)");
        }
        return results;
    }

    /** Send a tiny request, to test the provider, model, key and network settings. */
    public static String check(AiClient client) {
        String answer = client.complete("You answer with JSON only.", "Reply with exactly this JSON object: {\"ok\": true}");
        Object data = AiClient.extractJson(answer);
        if (!(data instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("ok")))) {
            throw new AiError(client.settings().label() + " answered, but not as asked: " + AiClient.cut(answer, 200));
        }
        return client.settings().label() + " answered correctly.";
    }
}
