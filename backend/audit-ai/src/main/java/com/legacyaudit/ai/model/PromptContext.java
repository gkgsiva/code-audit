package com.legacyaudit.ai.model;

import com.legacyaudit.ai.copilot.CopilotFiles;
import com.legacyaudit.ai.enrich.SchemaCheck;
import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.reports.Comments;
import com.legacyaudit.reports.Flows;
import com.legacyaudit.reports.RunData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a model is shown for one enrichment stage, within a character budget, exactly as the
 * Python tool built it (so the same model gets the same prompt).
 *
 * <p>Copilot in VS Code reads the facts itself, with tools. A model called over an API cannot, so
 * this packs the same material into the prompt: the agent's rules and the stage instructions (the
 * very files Copilot gets), the stage's JSON Schema, the scan summary, the fact files the stage
 * reads ({@link Stages}), earlier stages it builds on and, for the functional and component
 * stages, the source of every batch script and program with its comments, the code of every
 * desktop window and ASP.NET page and of every web service operation the application offers. Long
 * lists are cut with a note saying how many rows were left out. Credentials in source text are
 * masked. Lengths count characters (code points) as Python does.
 */
public final class PromptContext {

    static final int ROW_CHARS = 1500; // one fact row longer than this is cut
    static final int SOURCE_LINES = 150; // lines of each batch source file shown
    static final int SCREEN_LINES = 120; // lines of each window's code shown
    static final int HANDLER_LINES = 40; // lines of each web service operation shown, from its declaration
    public static final Set<String> SOURCE_STAGES = Set.of("functional", "component_docs");
    // Screens whose logic lives in the screen's own code (event handlers, code-behind): desktop
    // windows and ASP.NET pages. Web pages (JSP, Angular...) reach their logic through services.
    static final Set<String> CODE_SCREENS = Set.of("vb6", "winforms", "wpf", "swing", "awt", "javafx", "aspx");
    private static final Pattern VB6_ATTR = Pattern.compile("^Attribute VB_", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESIGNER = Pattern.compile("\\.Designer(\\.(vb|cs))$", Pattern.CASE_INSENSITIVE);
    private static final JsonMapper JSON = JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();

    static final String HEADLESS = """
            ## Working without tools

            You are called through an API by `legacy-audit enrich`, not in VS Code: you cannot run commands,
            open files or ask questions. Everything you may use is in the message below (scan summary, fact
            files, earlier stages and, where useful, source excerpts). Where a list says rows were left out,
            do not guess what they hold.

            Answer with ONE JSON object for the requested stage, matching the JSON Schema given, and nothing
            else (no Markdown fences, no comments). Leave `meta.built_from` out. The tool validates your
            answer and, if it finds errors, sends them back for you to fix. When you would have stopped to
            ask (rule 6), record the question as an assumption or open question in the JSON instead.""";

    /** The system and the user prompt of one stage. */
    public record Prompts(String system, String user) {}

    private PromptContext() {}

    // ---- Python string lengths and slices (code points) ------------------------------------

    static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Python's {@code s[:n]}, a negative {@code n} counting from the end. */
    static String head(String s, int n) {
        int total = len(s);
        int k = n < 0 ? Math.max(0, total + n) : Math.min(n, total);
        return s.substring(0, s.offsetByCodePoints(0, k));
    }

    private static int sum(List<String> parts) {
        int n = 0;
        for (String p : parts) {
            n += len(p);
        }
        return n;
    }

    // ---- blocks ------------------------------------------------------------------------------

    static String compact(Object row) {
        if (row instanceof Map<?, ?> m) {
            Map<Object, Object> kept = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                boolean empty = v == null || "".equals(v) || v instanceof List<?> l && l.isEmpty() || v instanceof Map<?, ?> mm && mm.isEmpty();
                if (!empty) {
                    kept.put(k, v);
                }
            });
            row = kept;
        }
        String text = PyJson.tight(row, false);
        return len(text) <= ROW_CHARS ? text : head(text, ROW_CHARS - 12) + "...\"(cut)\"";
    }

    /** A fact file as one compact JSON row per line, cut at {@code budget} characters. */
    static String rowsBlock(String title, Object data, int budget) {
        if (data instanceof Map<?, ?> m && !(m.get("items") instanceof List<?>)) {
            String text = PyJson.tight(data, false);
            String cut = len(text) <= budget ? "" : " (cut)";
            return "### " + title + cut + "\n" + head(text, budget) + "\n";
        }
        List<?> rows = data instanceof Map<?, ?> m ? (List<?>) m.get("items") : data instanceof List<?> l ? l : List.of();
        List<String> out = new ArrayList<>();
        int used = 0;
        for (Object row : rows) {
            String line = compact(row);
            if (used + len(line) > budget && !out.isEmpty()) {
                break;
            }
            out.add(line);
            used += len(line) + 1;
        }
        String note = out.size() == rows.size() ? "" : " (" + (rows.size() - out.size()) + " of " + rows.size() + " rows left out)";
        return "### " + title + ": " + rows.size() + " rows" + note + "\n" + String.join("\n", out) + "\n";
    }

    /** The first {@code limit} lines of a file as an editor shows them, secrets masked. */
    static List<String> read(Path path, int limit) {
        if (path == null) {
            return List.of();
        }
        try {
            List<String> lines = TextUtil.splitLines(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            List<String> out = new ArrayList<>();
            for (String x : lines.subList(0, Math.min(limit, lines.size()))) {
                out.add(Comments.maskSecrets(x));
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    static Object load(Path path) {
        try {
            return JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), Object.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String num(int i) {
        return String.format("%4d", i);
    }

    /**
     * Each batch (as on the Flows page) with its steps, then the start of every source file it
     * runs, numbered, so the model can read comments and cite lines.
     */
    static String batchSources(RunData rd, int budget) {
        List<String> parts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int used = 0;
        List<Flows.Flow> batches = Flows.batchFlows(rd);
        for (Flows.Flow f : batches) {
            Object schedule = f.schedule() == null ? null : f.schedule().get("text");
            List<String> steps = new ArrayList<>();
            for (Map<String, Object> st : f.steps()) {
                steps.add(Py.str(st.get("name")));
            }
            String head = "### Batch " + f.name() + " (id " + f.id() + "; " + (Py.truthy(schedule) ? Py.str(schedule) : "no schedule found")
                    + ")\nsteps: " + String.join(", ", steps) + "\n";
            parts.add(head);
            used += len(head);
            for (String rel : f.files()) {
                if (seen.contains(rel) || used >= budget) {
                    continue;
                }
                seen.add(rel);
                List<String> lines = read(rd.absPath(rel), SOURCE_LINES);
                if (lines.isEmpty()) {
                    continue;
                }
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < lines.size(); i++) {
                    body.append(i > 0 ? "\n" : "").append(num(i + 1)).append(": ").append(lines.get(i));
                }
                String block = "#### " + rel + " (first " + lines.size() + " lines)\n" + body + "\n";
                if (used + len(block) > budget) {
                    block = head(block, Math.max(0, budget - used)) + "\n(cut)\n";
                }
                parts.add(block);
                used += len(block);
            }
        }
        if (batches.isEmpty()) {
            return "";
        }
        return "## Batch sources (comments included)\n\n" + String.join("", parts);
    }

    /** Lines {@code start}.. of a file (1-based), numbered as in the file, secrets masked. */
    static List<String> numbered(Path path, int start, int limit) {
        if (path == null) {
            return List.of();
        }
        List<String> lines = read(path, start - 1 + limit);
        List<String> out = new ArrayList<>();
        for (int i = start - 1; i < lines.size(); i++) {
            out.add(num(i + 1) + ": " + lines.get(i));
        }
        return out;
    }

    /** Where the code of a VB6 form or control starts: after its layout and attributes. */
    static int codeStart(Path path) {
        List<String> head = read(path, 2000);
        int last = 0;
        for (int i = 0; i < head.size(); i++) {
            if (VB6_ATTR.matcher(head.get(i)).find()) {
                last = i + 1;
            }
        }
        return last + 1;
    }

    /**
     * The files holding a screen's code: the code file next to a WinForms designer file, the
     * code-behind of a WPF window or ASP.NET page, else the screen's own file.
     */
    static List<String> screenFiles(RunData rd, Map<String, Object> scr) {
        String file = Py.truthy(scr.get("file")) ? Py.str(scr.get("file")) : "";
        Set<String> known = new HashSet<>();
        for (Map<String, Object> f : rd.items("inventory")) {
            known.add(Py.str(f.get("path")));
        }
        String base = DESIGNER.matcher(file).replaceAll("$1");
        List<String> out = new ArrayList<>();
        if (!base.equals(file) && known.contains(base)) {
            out.add(base);
        }
        for (String ext : List.of(".cs", ".vb")) {
            if (known.contains(file + ext)) {
                out.add(file + ext);
            }
        }
        return out.isEmpty() ? List.of(file) : out;
    }

    /**
     * The code of each desktop window and ASP.NET page (event handlers, code-behind): the business
     * logic and database calls of these screens are written there, not in services.
     */
    static String screenSources(RunData rd, int budget) {
        List<String> parts = new ArrayList<>();
        int used = 0;
        List<Map<String, Object>> screens = new ArrayList<>();
        for (Map<String, Object> s : rd.items("screens")) {
            if (CODE_SCREENS.contains(s.get("technology"))) {
                screens.add(s);
            }
        }
        screens.sort(Comparator.<Map<String, Object>, Boolean>comparing(s -> Py.truthy(s.get("partial")))
                .thenComparing(s -> Py.str(s.get("name")), PyJson::compareCodePoints));
        for (Map<String, Object> scr : screens) {
            if (used >= budget) {
                parts.add("(cut: " + screens.size() + " screens in all)\n");
                break;
            }
            String head = "### Screen " + Py.str(scr.get("name")) + " (id " + Py.str(scr.get("id")) + "; " + Py.str(scr.get("technology")) + ")\n";
            StringBuilder body = new StringBuilder();
            for (String rel : screenFiles(rd, scr)) {
                Path path = rd.absPath(rel);
                if (path == null) {
                    continue;
                }
                String lower = rel.toLowerCase(Locale.ROOT);
                int start = lower.endsWith(".frm") || lower.endsWith(".ctl") ? codeStart(path) : 1;
                List<String> lines = numbered(path, start, SCREEN_LINES);
                if (!lines.isEmpty()) {
                    body.append("#### ").append(rel).append(" (lines ").append(start).append('-').append(start + lines.size() - 1).append(")\n");
                    body.append(String.join("\n", lines)).append('\n');
                }
            }
            String block = head + body;
            if (used + len(block) > budget) {
                block = head(block, Math.max(0, budget - used)) + "\n(cut)\n";
            }
            parts.add(block);
            used += len(block);
        }
        if (screens.isEmpty()) {
            return "";
        }
        return "## Screen code (desktop windows and ASP.NET pages)\n\n" + String.join("", parts);
    }

    /**
     * The code of each operation of the web services the application offers, from its declaration,
     * so the model can say what each service does and cite the line.
     */
    static String handlerSources(RunData rd, int budget) {
        List<String> parts = new ArrayList<>();
        int used = 0;
        List<Map<String, Object>> offered = new ArrayList<>();
        for (Map<String, Object> w : rd.items("web_services")) {
            if ("offered".equals(w.get("side"))) {
                offered.add(w);
            }
        }
        offered.sort(Comparator.comparing(w -> Py.str(w.get("name")), PyJson::compareCodePoints));
        for (Map<String, Object> ws : offered) {
            Object style = ws.get("style");
            String head = "### Web service " + Py.str(ws.get("name")) + " (id " + Py.str(ws.get("id")) + "; "
                    + (Py.truthy(style) ? Py.str(style) : "") + ")\n";
            StringBuilder body = new StringBuilder();
            Object ops = ws.get("operations");
            for (Object o : ops instanceof List<?> l ? l : List.of()) {
                Map<?, ?> op = (Map<?, ?>) o;
                Path path = Py.truthy(op.get("file")) ? rd.absPath(Py.str(op.get("file"))) : null;
                long line = Py.truthy(op.get("line")) ? ((Number) op.get("line")).longValue() : 1;
                List<String> lines = numbered(path, (int) Math.max(1, line - 3), HANDLER_LINES);
                if (!lines.isEmpty()) {
                    Object handler = op.get("handler");
                    body.append("#### ").append(Py.str(op.get("name"))).append(" (")
                            .append(Py.truthy(handler) ? Py.str(handler) : Py.str(op.get("file"))).append(")\n");
                    body.append(String.join("\n", lines)).append('\n');
                }
            }
            if (body.isEmpty()) {
                continue;
            }
            String block = head + body;
            if (used + len(block) > budget) {
                parts.add(head(block, Math.max(0, budget - used)) + "\n(cut)\n");
                break;
            }
            parts.add(block);
            used += len(block);
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "## Web service code (operations offered)\n\n" + String.join("", parts);
    }

    static String stagePrompt() {
        String text = CopilotFiles.asset("copilot/prompts/audit-enrich.prompt.md");
        // the first line is the VS Code input line; the rest is the same guidance Copilot follows
        return text.replaceFirst("^Enrich the scan facts.*\n", "");
    }

    private static String strip(String s, char c) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == c) {
            a++;
        }
        while (b > a && s.charAt(b - 1) == c) {
            b--;
        }
        return s.substring(a, b);
    }

    /** The system and user prompt for {@code stage} on the run in {@code runDir}. */
    public static Prompts build(Path runDir, Stages.Stage stage, int budget, RunData rd) {
        String system = CopilotFiles.asset("copilot/agent.md") + "\n\n" + HEADLESS;
        String schema = SchemaCheck.schemaText(stage.name());
        Map<String, Object> goal = Status.target(runDir);
        String fixed = "# Stage " + stage.number() + ": " + stage.title() + " (`" + stage.name() + "`)\n\n"
                + "Write `" + stage.file() + "` for this stage only. Start with "
                + "`\"meta\": {\"stage\": \"" + stage.name() + "\"}`.\n\n"
                + "**Migration target: " + Py.str(goal.get("label")) + "** (`data/target.json`, profile "
                + "`" + Py.str(goal.get("profile")) + "`). Follow the rules for this target in the agent rules.\n\n"
                + "## How to enrich (the same guidance the VS Code agent follows)\n\n" + stagePrompt() + "\n\n"
                + "## JSON Schema of `" + stage.file() + "`\n\n" + schema + "\n\n";
        int left = Math.max(budget - len(fixed), 4000);
        Path data = runDir.resolve("data");
        Object runObj = load(runDir.resolve("run.json"));
        Map<?, ?> run = runObj instanceof Map<?, ?> m && !m.isEmpty() ? m : Map.of();
        Object summaryObj = load(data.resolve("audit-summary.json"));
        Object summary = Py.truthy(summaryObj) ? summaryObj : new LinkedHashMap<>();
        Map<String, Object> context = new LinkedHashMap<>();
        for (String k : List.of("application", "coverage", "overrides", "run_id", "target")) {
            context.put(k, run.get(k));
        }
        List<String> blocks = new ArrayList<>();
        blocks.add(rowsBlock("run.json (application, input coverage, overrides)", context, Math.floorDiv(left, 10)));
        blocks.add(rowsBlock("audit-summary.json", summary, Math.floorDiv(left, 10)));
        left -= sum(blocks);
        String sources = "";
        if (SOURCE_STAGES.contains(stage.name())) {
            rd = rd != null ? rd : RunData.load(runDir);
            sources = batchSources(rd, Math.floorDiv(left, 6));
            sources += (sources.isEmpty() ? "" : "\n") + screenSources(rd, Math.floorDiv(left, 6));
            sources += (sources.isEmpty() ? "" : "\n") + handlerSources(rd, Math.floorDiv(left, 10));
            sources = strip(sources, '\n');
            left -= len(sources);
        }
        List<String[]> upstream = new ArrayList<>();
        for (String n : stage.after()) {
            Path p = runDir.resolve("enrichment").resolve(n + ".json");
            if (Files.exists(p)) {
                upstream.add(new String[] {n, p.toString()});
            }
        }
        if (!upstream.isEmpty()) {
            int each = Math.floorDiv(Math.floorDiv(left, 5), upstream.size());
            List<String> ups = new ArrayList<>();
            for (String[] u : upstream) {
                ups.add(rowsBlock("Earlier stage " + u[0] + ".json", load(Path.of(u[1])), each));
            }
            blocks.addAll(ups);
            left -= sum(ups);
        }
        List<String> kinds = new ArrayList<>();
        for (String k : stage.inputs()) {
            if (Files.exists(data.resolve(Stages.factFile(k)))) {
                kinds.add(k);
            }
        }
        for (String kind : kinds) {
            int each = Math.max(Math.floorDiv(left, Math.max(kinds.size(), 1)), 2000);
            blocks.add(rowsBlock("data/" + Stages.factFile(kind), load(data.resolve(Stages.factFile(kind))), each));
        }
        String user = fixed + "## Scan facts\n\n" + String.join("\n", blocks) + (sources.isEmpty() ? "" : "\n" + sources);
        return new Prompts(system, user);
    }

    /** The follow-up prompt that sends the validator's errors back to the model. */
    public static String repairPrompt(String answer, List<String> errors) {
        List<String> listed = new ArrayList<>();
        for (String e : errors.subList(0, Math.min(60, errors.size()))) {
            listed.add("- " + e);
        }
        return "The validator rejected your JSON with these errors:\n\n" + String.join("\n", listed)
                + "\n\nHere is your previous answer:\n\n" + answer + "\n\n"
                + "Answer again with the whole corrected JSON object, and nothing else.";
    }
}
