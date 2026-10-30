package com.legacyaudit.ai.enrich;

import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.reports.RunData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code legacy-audit validate}: checks the enrichment (written by Copilot or by {@code enrich})
 * against the scan. Per stage file, in order:
 *
 * <ol>
 *   <li>the JSON Schema ({@link SchemaCheck}): shape, choices, and every claim citing evidence or
 *       saying {@code assumption: true};
 *   <li>references: ids in {@code *_id} / {@code *_ids} fields exist in the facts, component names
 *       exist;
 *   <li>numbers come from the facts: a figure's value equals the scan's at its {@code metric} path
 *       unless {@code estimate: true}; counts written in prose ("12 services") that differ are
 *       warnings, since prose may talk about a subset;
 *   <li>evidence: each cited file is in the scanned source and the line is inside it;
 *   <li>suggestions follow the target in {@code data/target.json} (warnings).
 * </ol>
 *
 * <p>A stage with no errors gets {@code meta.built_from} stamped, then {@code status.json} is
 * rewritten. Messages and their order are the Python tool's.
 */
public final class Validator {

    /** One finding: {@code error} or {@code warning}, the stage, where in the file, the text. */
    public record Message(String level, String stage, String where, String text) {
        @Override
        public String toString() {
            return level + ": " + stage + (where.isEmpty() ? "" : " at " + where) + ": " + text;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level);
            m.put("stage", stage);
            m.put("where", where);
            m.put("text", text);
            return m;
        }
    }

    /** What one validation found, which stages it checked and stamped, and the new status. */
    public static final class Report {
        public final Path runDir;
        public final List<Message> messages = new ArrayList<>();
        public final List<String> checked = new ArrayList<>();
        public final List<String> stamped = new ArrayList<>();
        public Map<String, Object> status = new LinkedHashMap<>();

        Report(Path runDir) {
            this.runDir = runDir;
        }

        public List<Message> errors() {
            return messages.stream().filter(m -> m.level.equals("error")).toList();
        }

        public List<Message> warnings() {
            return messages.stream().filter(m -> m.level.equals("warning")).toList();
        }

        public boolean ok() {
            return errors().isEmpty();
        }
    }

    /** Prose noun to the summary path of the count it must agree with. */
    static final Map<String, String> PROSE_COUNTS = new LinkedHashMap<>();

    static {
        PROSE_COUNTS.put("services", "counts.services.total");
        PROSE_COUNTS.put("screens", "counts.screens.total");
        PROSE_COUNTS.put("sql statements", "counts.sql.total");
        PROSE_COUNTS.put("integrations", "counts.integrations.total");
        PROSE_COUNTS.put("web services", "counts.web_services.total");
        PROSE_COUNTS.put("batch jobs", "counts.batch_jobs.total");
        PROSE_COUNTS.put("libraries", "counts.libraries.total");
        PROSE_COUNTS.put("vulnerable libraries", "counts.vulnerable_libraries.total");
        PROSE_COUNTS.put("components", "totals.components");
        PROSE_COUNTS.put("findings", "totals.findings");
        PROSE_COUNTS.put("stored procedures", "counts.stored_procedures");
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern PROSE = Pattern.compile("(?<![\\w.,])(\\d[\\d,]*)\\s+("
            + String.join("|", PROSE_COUNTS.keySet().stream()
                    .sorted(Comparator.comparingInt(String::length).reversed()).toList())
            + ")\\b", FLAGS);
    private static final Pattern HEDGE = Pattern.compile("(about|around|approximately|roughly|~|over|under|up to|at least|"
            + "some|of the|of these|of them|out of|estimated?)\\s*$", FLAGS);
    // "Angular 21 components", "Java 25 services": a product version, not a count
    private static final Pattern VERSIONED = Pattern.compile("\\b(Angular|AngularJS|Java|JDK|Spring Boot|Spring|Node\\.js|Node|\\.NET|NET|PostgreSQL|"
            + "Python|Oracle|WebSphere|WebLogic|Tomcat|JBoss|WildFly|Windows|VB|Visual Basic)\\s*$", FLAGS);
    static final Set<String> COMPONENT_KEYS = Set.of("component", "components", "replaces");
    // Suggestions that belong to another target than WildFly (the code moves as it is there).
    private static final Pattern OTHER_TARGET = Pattern.compile("\\bSpring Boot\\b|\\bCloud Run\\b|\\bAngular 2\\d\\b", FLAGS);
    private static final Set<String> MIGRATION_KEYS = Set.of("migration", "migration_notes");
    /** The stages that suggest a target (none of them run when the target is none). */
    public static final Set<String> TARGET_STAGES = Set.of("service_mapping", "data_migration", "dependency_plan",
            "target_architecture", "plan");
    private static final Object MISSING = new Object();
    private static final JsonMapper JSON = JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();

    private final RunData rd;
    private final Map<String, Object> facts;
    private final Set<String> components = new HashSet<>();
    private final Map<Path, Integer> lineCounts = new HashMap<>();
    private final Map<String, Object> target;

    private Validator(Path runDir) {
        rd = RunData.load(runDir);
        facts = new LinkedHashMap<>(rd.summary);
        if (rd.effort != null) {
            facts.put("effort", rd.effort);
        }
        for (Map<String, Object> c : rd.items("components")) {
            components.add(Py.str(c.get("name")));
        }
        target = Status.target(runDir);
    }

    /** Validate the enrichment of one run (all stages, or {@code stages}); stamp what passes. */
    public static Report validate(Path runDir, List<String> stages, boolean restamp) {
        Status.syncFromLatest(runDir);
        Report report = new Report(runDir);
        Path edir = Status.enrichmentDir(runDir);
        Set<String> unknown = new TreeSet<>(stages == null ? List.of() : stages);
        unknown.removeAll(Stages.BY_NAME.keySet());
        for (String name : unknown) {
            report.messages.add(new Message("error", name, "", "no such stage"));
        }
        Validator checker = new Validator(runDir);
        Set<String> active = new HashSet<>();
        Status.activeStages(runDir).forEach(s -> active.add(s.name()));
        for (Stages.Stage stage : Stages.STAGES) {
            if (stages != null && !stages.isEmpty() && !stages.contains(stage.name())) {
                continue;
            }
            Path path = edir.resolve(stage.file());
            if (!Files.exists(path)) {
                continue;
            }
            if (!active.contains(stage.name())) {
                report.messages.add(new Message("warning", stage.name(), "", "not needed: the target is none (audit only)"));
                continue;
            }
            report.checked.add(stage.name());
            List<Message> msgs = new ArrayList<>();
            Object data;
            try {
                data = JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), Object.class);
                checker.check(stage, data, msgs);
            } catch (JacksonException e) {
                msgs.add(new Message("error", stage.name(), "", "not valid JSON: " + e.getOriginalMessage()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            report.messages.addAll(msgs);
            if (msgs.stream().noneMatch(m -> m.level.equals("error")) && Status.stamp(runDir, stage, restamp)) {
                report.stamped.add(stage.name());
            }
        }
        report.status = Files.isDirectory(edir) ? Status.write(runDir) : Status.compute(runDir);
        Status.syncToLatest(runDir);
        return report;
    }

    void check(Stages.Stage stage, Object data, List<Message> out) {
        BiConsumer<String[], String> add = (lw, text) -> out.add(new Message(lw[0], stage.name(), lw[1], text));
        List<SchemaCheck.Error> errors = SchemaCheck.check(stage.name(), data);
        if (!errors.isEmpty()) {
            for (SchemaCheck.Error e : errors) {
                add.accept(new String[] {"error", e.loc()}, e.msg());
            }
            return;
        }
        Map<?, ?> map = (Map<?, ?>) data;
        Object metaStage = ((Map<?, ?>) map.get("meta")).get("stage");
        if (!stage.name().equals(metaStage)) {
            add.accept(new String[] {"error", "meta.stage"}, "is '" + Py.str(metaStage) + "', expected '" + stage.name() + "'");
        }
        Map<Object, Object> body = new LinkedHashMap<>(map);
        body.remove("meta");
        references(body, add);
        figures(data, add);
        evidence(data, add);
        prose(data, add);
        target(stage, body, add);
    }

    /** {@code (path, key, value)} for every key of every object in the tree, depth first. */
    record Entry(String path, String key, Object value) {
        String where() {
            return path.isEmpty() ? key : path + "." + key;
        }
    }

    static List<Entry> walk(Object node) {
        List<Entry> out = new ArrayList<>();
        walk(node, "", out);
        return out;
    }

    private static void walk(Object node, String path, List<Entry> out) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                out.add(new Entry(path, k, e.getValue()));
                walk(e.getValue(), path.isEmpty() ? k : path + "." + k, out);
            }
        } else if (node instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                walk(l.get(i), path + "[" + i + "]", out);
            }
        }
    }

    private static void dicts(Object node, String path, List<Map.Entry<String, Map<?, ?>>> out) {
        if (node instanceof Map<?, ?> m) {
            out.add(Map.entry(path, m));
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                dicts(e.getValue(), path.isEmpty() ? k : path + "." + k, out);
            }
        } else if (node instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                dicts(l.get(i), path + "[" + i + "]", out);
            }
        }
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> l ? l : java.util.Collections.singletonList(value);
    }

    private void references(Object body, BiConsumer<String[], String> add) {
        for (Entry e : walk(body)) {
            if (e.key.endsWith("_id") || e.key.endsWith("_ids")) {
                for (Object v : asList(e.value)) {
                    if (v instanceof String s && !rd.byId.containsKey(s)) {
                        add.accept(new String[] {"error", e.where()}, "id '" + s + "' is not in the scan facts");
                    }
                }
            } else if (COMPONENT_KEYS.contains(e.key)) {
                for (Object v : asList(e.value)) {
                    if (v instanceof String s && !components.contains(s)) {
                        add.accept(new String[] {"error", e.where()}, "component '" + s + "' is not in components.json");
                    }
                }
            }
        }
    }

    /** The value at a dotted path of the summary (and {@code effort.*}), or {@link #MISSING}. */
    static Object lookup(Map<String, Object> facts, String metric) {
        Object d = facts;
        for (String part : metric.split("\\.", -1)) {
            if (!(d instanceof Map<?, ?> m) || !m.containsKey(part)) {
                return MISSING;
            }
            d = m.get(part);
        }
        return d;
    }

    /** Python's {@code a == b} for booleans, numbers within 1e-6, else {@code str(a) == str(b)}. */
    static boolean same(Object a, Object b) {
        if (a instanceof Boolean || b instanceof Boolean) {
            Object x = a instanceof Boolean ba ? (ba ? 1L : 0L) : a;
            Object y = b instanceof Boolean bb ? (bb ? 1L : 0L) : b;
            if (x instanceof Number nx && y instanceof Number ny) {
                return nx.doubleValue() == ny.doubleValue();
            }
            return false;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Math.abs(na.doubleValue() - nb.doubleValue()) < 1e-6;
        }
        return Py.str(a).equals(Py.str(b));
    }

    private void figures(Object data, BiConsumer<String[], String> add) {
        List<Map.Entry<String, Map<?, ?>>> all = new ArrayList<>();
        dicts(data, "", all);
        for (Map.Entry<String, Map<?, ?>> pd : all) {
            Map<?, ?> d = pd.getValue();
            String path = pd.getKey();
            if (!d.containsKey("metric") || !d.containsKey("value") || !d.containsKey("label")) {
                continue;
            }
            Object metric = d.get("metric");
            boolean estimate = Py.truthy(d.containsKey("estimate") ? d.get("estimate") : false);
            if (!Py.truthy(metric)) {
                if (!estimate) {
                    add.accept(new String[] {"error", path}, "figure '" + Py.str(d.get("label")) + "' has no metric: add the summary "
                            + "path it comes from, or mark it estimate: true and say how in note");
                }
                continue;
            }
            Object actual = lookup(facts, Py.str(metric));
            if (actual == MISSING) {
                if (!estimate) {
                    add.accept(new String[] {"error", path}, "metric '" + Py.str(metric) + "' is not in audit-summary.json or effort.json");
                }
                continue;
            }
            if (!estimate && !same(d.get("value"), actual)) {
                add.accept(new String[] {"error", path}, "figure '" + Py.str(d.get("label")) + "' says " + Py.str(d.get("value"))
                        + " but the scan says " + Py.str(actual) + " (" + Py.str(metric) + "); copy the scan's number or mark it estimate: true");
            }
        }
    }

    /** Lines as the scan and VS Code count them: {@code \r\n}, {@code \n} and a lone {@code \r}. */
    int lines(Path p) {
        return lineCounts.computeIfAbsent(p, k -> {
            try {
                byte[] data = Files.readAllBytes(k);
                int breaks = 0;
                for (int i = 0; i < data.length; i++) {
                    if (data[i] == '\n') {
                        breaks++;
                    } else if (data[i] == '\r' && (i + 1 >= data.length || data[i + 1] != '\n')) {
                        breaks++;
                    }
                }
                boolean endsWithBreak = data.length > 0 && (data[data.length - 1] == '\n' || data[data.length - 1] == '\r');
                return breaks + (endsWithBreak || data.length == 0 ? 0 : 1);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void evidence(Object data, BiConsumer<String[], String> add) {
        for (Entry e : walk(data)) {
            if (!e.key.equals("evidence") || !(e.value instanceof List<?> list)) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                String where = e.path.isEmpty() ? "evidence[" + i + "]" : e.path + ".evidence[" + i + "]";
                Map<?, ?> ev = (Map<?, ?>) list.get(i);
                Object file = ev.get("file");
                Object line = ev.get("line");
                Path p = Py.truthy(file) ? rd.absPath(Py.str(file)) : null;
                if (p == null || !Files.isRegularFile(p)) {
                    if (!rd.roots.isEmpty()) {
                        add.accept(new String[] {"error", where}, "evidence file '" + Py.str(file) + "' is not in the scanned source");
                    }
                    continue;
                }
                if (line != null) {
                    long n = line instanceof Number num ? num.longValue() : Long.parseLong(Py.str(line).strip());
                    int count = lines(p);
                    if (n < 1 || n > count) {
                        add.accept(new String[] {"error", where}, "line " + Py.str(line) + " is outside '" + Py.str(file) + "' (" + count + " lines)");
                    }
                }
            }
        }
    }

    /** Suggestions must follow the target in {@code data/target.json} (decision 161). */
    private void target(Stages.Stage stage, Object body, BiConsumer<String[], String> add) {
        String profile = Py.str(target.get("profile"));
        String label = Py.str(target.get("label"));
        for (Entry e : walk(body)) {
            if (!(e.value instanceof String value) || value.isBlank()) {
                continue;
            }
            if (profile.equals(AuditConfig.Target.NONE) && MIGRATION_KEYS.contains(e.key)) {
                add.accept(new String[] {"warning", e.where()}, "the target is none (audit only): leave migration text out");
            } else if (profile.equals(AuditConfig.Target.WILDFLY)
                    && (TARGET_STAGES.contains(stage.name()) || MIGRATION_KEYS.contains(e.key))) {
                Matcher hit = OTHER_TARGET.matcher(value);
                if (hit.find() && !e.key.equals("today") && !e.key.equals("mermaid")) {
                    add.accept(new String[] {"warning", e.where()}, "names '" + hit.group() + "', but the target is " + label
                            + ": the code moves as it is and only the database changes");
                }
            }
        }
    }

    private void prose(Object data, BiConsumer<String[], String> add) {
        for (Entry e : walk(data)) {
            if (!(e.value instanceof String value) || Set.of("mermaid", "metric", "label").contains(e.key)) {
                continue;
            }
            Matcher m = PROSE.matcher(value);
            while (m.find()) {
                String before = value.substring(0, m.start());
                if (HEDGE.matcher(before).find() || VERSIONED.matcher(before).find()) {
                    continue;
                }
                BigInteger n = new BigInteger(digits(m.group(1).replace(",", "")));
                String metric = PROSE_COUNTS.get(m.group(2).toLowerCase(java.util.Locale.ROOT));
                Object actual = lookup(facts, metric);
                if (actual != MISSING && (actual instanceof Integer || actual instanceof Long || actual instanceof BigInteger)
                        && !n.equals(new BigInteger(actual.toString()))) {
                    add.accept(new String[] {"warning", e.where()}, "text says '" + m.group() + "' but the scan counts "
                            + actual + " (" + metric + "); use the scan's number, or say it is a subset");
                }
            }
        }
    }

    /** Unicode digits as ASCII (Python's {@code int()} reads any decimal digit). */
    private static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(c -> sb.append(Character.digit(c, 10)));
        return sb.toString();
    }
}
