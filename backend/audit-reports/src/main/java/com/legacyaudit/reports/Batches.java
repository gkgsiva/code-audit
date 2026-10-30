package com.legacyaudit.reports;

import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One consolidated batch list: every batch with a short description and when it runs.
 *
 * <p>A batch is a batch flow ({@link Flows#batchFlows}): a scheduled box or job, or, when no
 * scheduler definition covers it, a batch component, a program or a listener. Each row says what
 * the batch does and when it runs, and where each of the two came from:
 *
 * <ul>
 *   <li><i>what it does</i>: the Copilot story's purpose (stage 12, labelled AI), else the
 *       scheduler's own description, else the first line of the code's header comment, else the
 *       scan's sentence;
 *   <li><i>when it runs</i>: the scheduler definition (JIL, JCL, crontab, Spring Batch), else the
 *       AI story's {@code when}, else a hint read from the code ({@code @Scheduled} or Quartz cron
 *       expressions, comments such as "nightly" or "runs at 02:00"), else "no schedule found" with
 *       the inputs that are missing.
 * </ul>
 *
 * <p>Hints are copied from the source with their file and line, never guessed; they are read at
 * report time from the scanned source, like the header comments.
 */
public final class Batches {

    private Batches() {}

    static final int MAX_FILES = 200; // files of one batch component read for schedule hints
    static final long MAX_BYTES = 512 * 1024;
    private static final Set<String> HINT_LANGS = Set.of(
            "java", "xml", "properties", "yaml", "shell", "sql", "plsql", "c", "proc", "cobol", "typescript", "javascript");

    private static final Pattern SCHEDULED = Rx.of("@Scheduled\\s*\\(([^)]*)\\)");
    // Node.js: cron.schedule('0 2 * * *', ...), new CronJob('...'), @Cron('...'), scheduleJob('...')
    private static final Pattern NODE_CRON = Rx.of(
            "(?:\\bcron\\.schedule|\\bnew\\s+CronJob|@Cron|\\bscheduleJob)\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]");
    private static final Pattern CRON_ARG = Rx.of("cron\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern RATE_ARG = Rx.of("(fixedRate|fixedDelay)\\s*=\\s*(\\d+)");
    private static final Pattern CRON_SETTING = Rx.of(
            "(?i)cron(?:[._-]?expression)?\\w*\\s*(?:[:=]|\"\\s*value\\s*=)\\s*[\"']?"
                    + "((?:[\\d*?/,LW#-]+|[A-Z]{3}(?:-[A-Z]{3})?)(?:\\s+(?:[\\d*?/,LW#-]+|[A-Z]{3}(?:-[A-Z]{3})?)){4,6})");
    private static final Pattern WORDS = Rx.of(
            "(?i)\\b(nightly|daily|weekly|monthly|hourly|quarterly|yearly|annually|"
                    + "every\\s+(?:day|night|morning|evening|hour|week|month|weekday|working day|"
                    + "\\d+\\s+(?:minutes?|mins?|hours?|days?))|"
                    + "each\\s+(?:day|night|morning|evening|month|week)|once\\s+a\\s+(?:day|week|month)|"
                    + "end[- ]of[- ](?:day|month|year)|month[- ]end|year[- ]end|"
                    + "(?:runs?|run|started|scheduled|launched)\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|h)?)\\b");
    private static final Pattern COMMENT = Rx.of("^\\s*(#|//|/\\*|\\*|--|rem\\b|::|\\*>|//\\*)", Rx.I);
    private static final Pattern DIGIT = Pattern.compile("\\d");

    /**
     * A cron line (5 fields) or a Spring/Quartz one (6-7 fields, seconds first) in words. Spring
     * numbers the days like cron (0 or 7 = Sunday), Quartz from 1 = Sunday.
     */
    static String cronText(String expr, boolean quartz) {
        List<String> parts = TextUtil.words(expr);
        if (parts.size() >= 6) {
            parts = new ArrayList<>(parts.subList(1, 6));
            if (quartz) {
                parts.set(4, TextUtil.sub(DIGIT, parts.get(4), m -> String.valueOf(Math.floorMod(Integer.parseInt(m.group()) - 1, 7))));
            }
        }
        return Py.str(Flows.parseSchedule(String.join(" ", parts), "cron").get("text"));
    }

    private record Hit(String text, int line) {}

    private static Hit hintInText(String text, String lang) {
        List<String> lines = TextUtil.splitLines(text);
        for (int no = 1; no <= lines.size(); no++) {
            String line = lines.get(no - 1);
            Matcher m = SCHEDULED.matcher(line);
            if (m.find()) {
                String args = m.group(1);
                Matcher c = CRON_ARG.matcher(args);
                if (c.find()) {
                    return new Hit(cronText(c.group(1), false) + " (@Scheduled cron \"" + c.group(1) + "\")", no);
                }
                Matcher r = RATE_ARG.matcher(args);
                if (r.find()) {
                    return new Hit("Runs every " + Math.floorDiv(Long.parseLong(r.group(2)), 1000L) + " s (@Scheduled " + r.group(1) + ")", no);
                }
            }
            if (lang.equals("typescript") || lang.equals("javascript")) {
                Matcher n = NODE_CRON.matcher(line);
                if (n.find() && TextUtil.words(n.group(1)).size() >= 5) {
                    return new Hit(cronText(n.group(1), false) + " (cron \"" + n.group(1) + "\")", no);
                }
            }
            Matcher s = CRON_SETTING.matcher(line);
            if (s.find() && List.of("xml", "properties", "yaml", "java").contains(lang)) {
                String expr = TextUtil.strip(s.group(1));
                return new Hit(cronText(expr, lang.equals("xml")) + " (cron \"" + expr + "\")", no);
            }
        }
        for (int no = 1; no <= Math.min(200, lines.size()); no++) {
            String line = lines.get(no - 1);
            if (COMMENT.matcher(line).lookingAt() || lang.equals("properties") || lang.equals("yaml")) {
                Matcher w = WORDS.matcher(line);
                if (w.find()) {
                    String phrase = Comments.maskSecrets(String.join(" ", TextUtil.words(w.group(1))));
                    int first = phrase.offsetByCodePoints(0, 1);
                    return new Hit(phrase.substring(0, first).toUpperCase(Locale.ROOT) + phrase.substring(first)
                            + ", says a comment in the code", no);
                }
            }
        }
        return null;
    }

    /**
     * The first schedule the code of a batch states: its own files first, then the rest of its
     * component (configuration holds Quartz triggers). {@code {text, file, line}} or null.
     */
    static Map<String, Object> scheduleHint(RunData rd, Flows.Flow f) {
        Map<String, String> langs = new LinkedHashMap<>();
        rd.items("inventory").forEach(i -> langs.put(D.str(i, "path"), D.s(i, "language")));
        List<String> paths = new ArrayList<>(D.unique(f.files()));
        if (f.component() != null && !f.component().isEmpty()) {
            List<String> more = new ArrayList<>();
            rd.fileComponent.forEach((p, c) -> {
                if (c.equals(f.component())) {
                    more.add(p);
                }
            });
            more.sort(PyJson::compareCodePoints);
            paths.addAll(more);
        }
        Set<String> seen = new HashSet<>();
        List<String> todo = D.unique(paths);
        for (String path : todo.subList(0, Math.min(MAX_FILES, todo.size()))) {
            String lang = langs.getOrDefault(path, "");
            if (seen.contains(path) || !HINT_LANGS.contains(lang)) {
                continue;
            }
            seen.add(path);
            Path p = rd.absPath(path);
            String text;
            try {
                if (p == null || !Files.isRegularFile(p) || Files.size(p) > MAX_BYTES) {
                    continue;
                }
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).toString();
            } catch (IOException e) {
                continue;
            }
            Hit hit = hintInText(text, lang);
            if (hit != null) {
                return D.of("text", hit.text(), "file", path, "line", (long) hit.line());
            }
        }
        return null;
    }

    /** The scan's own short description: the command or the first steps it runs. */
    private static String whatItRuns(RunData rd, Flows.Flow f) {
        Map<String, Object> job = rd.byId.getOrDefault(f.id(), Map.of());
        if (D.t(job, "command")) {
            return "Runs " + D.str(job, "command");
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> s : f.steps()) {
            if (D.t(s, "name")) {
                names.add(D.str(s, "name"));
            }
        }
        if (!names.isEmpty()) {
            String more = names.size() > 3 ? " and " + (names.size() - 3) + " more" : "";
            return "Runs " + String.join(", ", names.subList(0, Math.min(3, names.size()))) + more;
        }
        return f.summary();
    }

    private static List<String> missingInputs(RunData rd) {
        Map<String, Object> cov = new LinkedHashMap<>();
        rd.items("input_coverage").forEach(c -> cov.put(Py.str(D.get(c, "kind")), D.get(c, "status")));
        List<String> out = new ArrayList<>();
        if ("missing".equals(cov.get("autosys"))) {
            out.add("Autosys JIL");
        }
        if ("missing".equals(cov.get("crontab"))) {
            out.add("crontab");
        }
        return out;
    }

    /** One row per batch, scheduled or not, in name order. */
    public static List<Map<String, Object>> batchList(RunData rd, List<Flows.Flow> batchFlows, Map<String, String> said,
            Map<String, Map<String, Object>> stories) {
        List<String> missing = missingInputs(rd);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Flows.Flow f : batchFlows) {
            Map<String, Object> sched = f.schedule();
            String scheduler = D.s(sched, "scheduler");
            Map<String, Object> story = stories.getOrDefault(f.id(), Map.of());
            String what;
            String whatFrom;
            if (D.t(story, "purpose")) {
                what = D.str(story, "purpose");
                whatFrom = "AI (Copilot)";
            } else if (D.t(sched, "description")) {
                what = D.str(sched, "description");
                whatFrom = "scheduler";
            } else if (said.get(f.id()) != null && !said.get(f.id()).isEmpty()) {
                what = said.get(f.id());
                whatFrom = "code comment";
            } else {
                what = whatItRuns(rd, f);
                whatFrom = "scan";
            }
            Map<String, Object> hint = null;
            // A JCL job or a Spring Batch job alone does not say when it runs: look further.
            boolean scheduled = D.t(sched, "raw") || D.t(sched, "started_by");
            String when;
            String whenFrom;
            boolean noneOrEvent = scheduler.equals("none") || scheduler.equals("event");
            if (!noneOrEvent && scheduled) {
                when = D.s(sched, "text");
                whenFrom = scheduler;
            } else if (scheduler.equals("event")) {
                when = "On each message received (listener).";
                whenFrom = "code";
            } else if (D.t(story, "when")) {
                when = D.str(story, "when");
                whenFrom = "AI (Copilot)";
            } else if ((hint = scheduleHint(rd, f)) != null) {
                when = D.str(hint, "text");
                whenFrom = "code";
            } else {
                String gap = missing.isEmpty() ? "" : " (" + String.join(", ", missing) + " not provided)";
                when = "No schedule found" + gap + ".";
                whenFrom = "none";
            }
            String kind;
            if (scheduler.equals("none")) {
                kind = f.id().startsWith("component:") ? "batch component" : "program";
            } else if (scheduler.equals("event")) {
                kind = "listener";
            } else {
                kind = "box".equals(D.get(rd.byId.get(f.id()), "kind")) ? "box" : "job";
            }
            rows.add(D.of("id", f.id(), "name", f.name(), "slug", f.slug(), "kind", kind, "scheduler", noneOrEvent ? "" : scheduler,
                    "component", f.component(), "steps", (long) f.steps().size(), "what", what, "what_from", whatFrom, "when", when,
                    "when_from", whenFrom, "when_file", hint != null ? hint.get("file") : null,
                    "when_line", hint != null ? hint.get("line") : null, "evidence", f.evidence()));
        }
        rows.sort((a, b) -> {
            int c = PyJson.compareCodePoints(D.str(a, "name").toLowerCase(Locale.ROOT), D.str(b, "name").toLowerCase(Locale.ROOT));
            return c != 0 ? c : PyJson.compareCodePoints(D.str(a, "id"), D.str(b, "id"));
        });
        return rows;
    }
}
