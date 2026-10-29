package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autosys JIL ({@code audit-inputs/autosys/} and any {@code .jil}): {@code insert_job} /
 * {@code update_job} / {@code delete_job} with their attributes. Every job is a batch job
 * (scheduler {@code autosys}); a {@code BOX} is a container (kind {@code box}), a file watcher
 * ({@code FW}) kind {@code file-watcher} with its watched file an inbound {@code FILE}
 * integration. A job's command is read like a script line ({@link ShellAnalyzer#commandFacts});
 * condition predecessors become {@code depends-on} references between jobs.
 */
public final class AutosysAnalyzer implements Analyzer {

    static final List<String> JIL_ATTRS = List.of(
            "insert_job", "update_job", "delete_job", "override_job", "delete_box", "job_type",
            "box_name", "command", "machine", "owner", "permission", "date_conditions", "days_of_week",
            "start_times", "start_mins", "run_calendar", "exclude_calendar", "run_window", "condition",
            "description", "std_in_file", "std_out_file", "std_err_file", "alarm_if_fail",
            "alarm_if_terminated", "max_run_alarm", "min_run_alarm", "term_run_time", "n_retrys",
            "watch_file", "watch_interval", "watch_file_min_size", "profile", "timezone", "box_success",
            "box_failure", "box_terminator", "job_terminator", "job_load", "priority", "group",
            "application", "auto_delete", "auto_hold", "chk_files", "heartbeat_interval",
            "max_exit_success", "must_complete_times", "must_start_times", "send_notification",
            "notification_msg", "notification_id", "resources", "fail_codes", "success_codes", "envvars",
            "net_relationship", "ulimit", "avg_runtime");
    private static final Pattern ATTR = Rx.of("(?:^|\\s)(" + String.join("|", JIL_ATTRS) + "):\\s*", Rx.I);
    private static final Pattern CONDITION = Rx.of(
            "\\b(s|success|f|failure|d|done|n|notrunning|t|terminated|e|exitcode)\\s*\\(\\s*([^,)\\s]+)", Rx.I);
    private static final Pattern BLOCK_COMMENT = Rx.of("/\\*[\\s\\S]*?\\*/");
    private static final Pattern NOT_NEWLINE = Rx.of("[^\\n]");
    private static final Pattern QUOTED = Rx.of("\"[^\"]*\"");
    private static final List<String> FAILURE_ATTRS = List.of("alarm_if_fail", "alarm_if_terminated", "n_retrys", "max_run_alarm",
            "term_run_time", "box_failure", "send_notification", "notification_msg");
    private static final Map<String, String> JOB_TYPES = Map.of("c", "CMD", "cmd", "CMD", "b", "BOX", "box", "BOX", "f", "FW", "fw", "FW");
    private static final List<String> SCHEDULE_ATTRS = List.of("date_conditions", "days_of_week", "start_times", "start_mins",
            "run_calendar", "exclude_calendar", "run_window", "timezone");

    @Override
    public String name() {
        return "autosys";
    }

    @Override
    public String version() {
        return "2";
    }

    @Override
    public boolean supports(FileRecord file) {
        return file.language.equals("jil")
                || (file.path.toLowerCase(Locale.ROOT).startsWith("audit-inputs/autosys/") && file.category != Category.BINARY);
    }

    /** An attribute value and the line it is on. */
    record Value(String value, int line) {}

    /** One {@code insert_job} (or update/delete/override) with its attributes. */
    record Job(int line, String verb, String name, Map<String, Value> attrs) {}

    record Parsed(List<Job> jobs, List<Integer> unparsed) {}

    /** Where an attribute name starts and its value begins. */
    private record Start(int start, int end, String key) {}

    static Parsed parseJil(String text) {
        List<Job> jobs = new ArrayList<>();
        List<Integer> unparsed = new ArrayList<>();
        text = TextUtil.sub(BLOCK_COMMENT, text, m -> NOT_NEWLINE.matcher(m.group(0)).replaceAll(" "));
        Map<String, Value> current = null;
        int no = 0;
        for (String raw : TextUtil.splitLines(text)) {
            no++;
            String line = TextUtil.strip(raw);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // Several "attr: value" pairs may share a line: split on attribute names.
            List<int[]> quoted = new ArrayList<>();
            Matcher q = QUOTED.matcher(line);
            while (q.find()) {
                quoted.add(new int[] {q.start(), q.end()});
            }
            List<Start> starts = new ArrayList<>();
            Matcher m = ATTR.matcher(line);
            while (m.find()) {
                int s1 = m.start(1);
                boolean inQuote = false;
                for (int[] span : quoted) {
                    if (span[0] < s1 && s1 < span[1]) {
                        inQuote = true;
                        break;
                    }
                }
                if (!inQuote) {
                    starts.add(new Start(m.start(), m.end(), m.group(1)));
                }
            }
            if (starts.isEmpty() || (starts.get(0).start() > 0 && !TextUtil.strip(line.substring(0, starts.get(0).start())).isEmpty())) {
                unparsed.add(no);
                continue;
            }
            for (int k = 0; k < starts.size(); k++) {
                Start st = starts.get(k);
                int end = k + 1 < starts.size() ? starts.get(k + 1).start() : line.length();
                String key = st.key().toLowerCase(Locale.ROOT);
                String value = TextUtil.strip(TextUtil.strip(line.substring(st.end(), end)), "\"");
                if (key.equals("insert_job") || key.equals("update_job") || key.equals("delete_job") || key.equals("override_job")) {
                    current = new LinkedHashMap<>();
                    List<String> words = TextUtil.words(value);
                    jobs.add(new Job(no, key, value.isEmpty() ? "" : words.get(0), current));
                } else if (current != null) {
                    current.put(key, new Value(value, no));
                } else {
                    unparsed.add(no);
                }
            }
        }
        return new Parsed(jobs, unparsed);
    }

    static List<String> predecessors(String condition) {
        List<String> out = new ArrayList<>();
        Matcher m = CONDITION.matcher(condition);
        while (m.find()) {
            String name = TextUtil.beforeFirst(m.group(2), "^");
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        String path = file.path;
        facts.coverage.lines = TextUtil.lineCount(text);
        Parsed parsed = parseJil(text);
        for (int no : parsed.unparsed()) {
            facts.coverage.gap(no, no, "not a JIL statement");
        }
        Map<String, Job> jobs = new LinkedHashMap<>();
        for (Job j : parsed.jobs()) {
            if (j.verb().equals("delete_job")) {
                jobs.remove(j.name());
            } else if ((j.verb().equals("update_job") || j.verb().equals("override_job")) && jobs.containsKey(j.name())) {
                jobs.get(j.name()).attrs().putAll(j.attrs());
            } else {
                jobs.put(j.name(), new Job(j.line(), j.verb(), j.name(), new LinkedHashMap<>(j.attrs())));
            }
        }
        if (!jobs.isEmpty()) {
            facts.add("tech_stack", BuildAnalyzer.tech("Autosys", null, "scheduler", path, jobs.values().iterator().next().line()));
        }
        String dialect = ctx.config().sqlDialect();
        for (Job j : jobs.values()) {
            job(path, j.name(), j.line(), j.attrs(), dialect, facts);
        }
        return facts;
    }

    private static void job(String path, String name, int line, Map<String, Value> attrs, String dialect, Facts facts) {
        java.util.function.Function<String, String> get = k -> attrs.containsKey(k) ? attrs.get(k).value() : null;
        String rawType = get.apply("job_type") == null || get.apply("job_type").isEmpty() ? "CMD" : get.apply("job_type");
        String jtype = JOB_TYPES.getOrDefault(rawType.toLowerCase(Locale.ROOT), rawType.toUpperCase(Locale.ROOT));
        String kind = jtype.equals("BOX") ? "box" : jtype.equals("FW") ? "file-watcher" : "job";
        String condition = get.apply("condition");
        List<String> preds = TextUtil.truthy(condition) ? predecessors(condition) : List.of();
        List<String> schedule = new ArrayList<>();
        for (String k : SCHEDULE_ATTRS) {
            if (TextUtil.truthy(get.apply(k))) {
                schedule.add(k + ": " + get.apply(k));
            }
        }
        List<String> onFailure = new ArrayList<>();
        for (String k : FAILURE_ATTRS) {
            if (TextUtil.truthy(get.apply(k))) {
                onFailure.add(k + ": " + get.apply(k));
            }
        }
        String jid = Fact.makeId("batch_job", "autosys", name);
        Doc job = Doc.of(Schemas.BATCH_JOB,
                "id", jid,
                "name", name,
                "scheduler", "autosys",
                "kind", kind,
                "description", get.apply("description"),
                "schedule", schedule.isEmpty() ? null : String.join("; ", schedule),
                "box", get.apply("box_name"),
                "command", get.apply("command"),
                "machine", get.apply("machine"),
                "condition", condition,
                "predecessors", new ArrayList<>(preds),
                "on_failure", onFailure,
                "evidence", new ArrayList<>(List.of(new Evidence(path, line, "insert_job: " + name + " job_type: " + jtype))));
        facts.add("batch_jobs", job);
        facts.symbol("autosys-job", name, path, line).owner(jid).data("kind", kind);
        String src = "batch_job:" + jid;
        String box = job.str("box");
        if (TextUtil.truthy(box)) {
            facts.ref("contains", src, name, box, List.of("autosys-job"), path, attrs.get("box_name").line()).data("reverse", "true");
        }
        for (String pred : preds) {
            facts.ref("depends-on", src, name, pred, List.of("autosys-job"), path, attrs.get("condition").line());
        }
        String command = job.str("command");
        if (TextUtil.truthy(command)) {
            facts.extend(ShellAnalyzer.commandFacts(command, path, attrs.get("command").line(), dialect, src, name));
        }
        String watch = get.apply("watch_file");
        if (kind.equals("file-watcher") && TextUtil.truthy(watch)) {
            facts.add("integrations", Integrations.integration("FILE", watch, "inbound", path, attrs.get("watch_file").line(), null, "autosys-fw",
                    Map.of("job", name)));
        }
    }
}
