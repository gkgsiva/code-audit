package com.legacyaudit.engine.store;

import com.legacyaudit.engine.components.Kinds;
import com.legacyaudit.engine.linker.Classify;
import com.legacyaudit.model.facts.Component;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Finding;
import com.legacyaudit.model.facts.InputCoverage;
import com.legacyaudit.model.facts.Origin;
import com.legacyaudit.model.facts.RunInfo;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.model.json.PyOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The headline counts (services, screens, SQL, integrations, jobs, libraries...), computed in one
 * place from the facts so the summary, the reports and the sample tests agree; and
 * {@code audit-summary.json}: totals and top-N lists, kept under 30 KB for the agent's context.
 */
public final class Summary {

    static final int MAX_BYTES = 30_000;
    static final List<String> PROCEDURE_TYPES = List.of("package", "procedure", "function");
    static final List<String> RUN_EDGES = List.of("invokes", "runs", "triggers", "calls", "includes");
    /** Programs and entry points that start batch work (the same lists as the Batch pages). */
    static final List<String> PROGRAM_SERVICES = List.of("c-executable", "cobol-program");
    static final List<String> PROGRAM_ENTRY = List.of("main", "scheduler", "spring-batch", "windows-service");
    static final Set<String> ONLINE_ROLES = Set.of("web", "online-mainframe", "frontend", "api", "desktop");
    static final Map<String, Integer> SEVERITY_RANK = Map.of("critical", 0, "high", 1, "medium", 2, "low", 3, "info", 4, "unknown", 5);
    /** The entity files in the order of the facts model. */
    public static final List<String> ENTITIES = List.of("inventory", "components", "entry_points", "services", "screens", "sql_statements",
            "db_objects", "integrations", "web_services", "dependencies", "batch_jobs", "libraries", "tech_stack", "findings", "metrics",
            "input_coverage");

    private Summary() {}

    /** Everything a run wrote, by entity. */
    public record Facts(List<FileRecord> inventory, List<Component> components, Map<String, List<Doc>> entities, List<Finding> findings,
            List<InputCoverage> coverage) {

        List<Doc> of(String name) {
            return entities.getOrDefault(name, List.of());
        }

        int size(String name) {
            return switch (name) {
                case "inventory" -> inventory.size();
                case "components" -> components.size();
                case "findings" -> findings.size();
                case "input_coverage" -> coverage.size();
                default -> of(name).size();
            };
        }
    }

    private static Map<String, Object> counter(Collection<String> values) {
        Map<String, Long> c = new TreeMap<>(PyJson::compareCodePoints);
        for (String v : values) {
            c.merge(String.valueOf(v), 1L, Long::sum);
        }
        return new LinkedHashMap<>(c);
    }

    private static <T> List<String> each(List<T> items, Function<T, String> f) {
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(f.apply(i)));
        return out;
    }

    private static boolean has(String s) {
        return s != null && !s.isEmpty();
    }

    /** The first evidence of a fact: (file, line), or null. */
    private static Evidence firstEvidence(Doc d) {
        List<Object> ev = d.list("evidence");
        if (ev.isEmpty()) {
            return null;
        }
        Object e = ev.get(0);
        if (e instanceof Evidence x) {
            return x;
        }
        Map<?, ?> m = (Map<?, ?>) e;
        return new Evidence((String) m.get("file"), m.get("line") instanceof Number n ? n.intValue() : null, null);
    }

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * Components a scheduler definition covers: a job is defined in it, or a job runs something
     * in it (directly or through the scripts and programs it starts).
     */
    private static Set<String> covered(Facts facts, Map<String, List<String>> runs, Map<String, String> owner) {
        List<Doc> jobs = facts.of("batch_jobs");
        Set<String> covered = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (Doc j : jobs) {
            if (has(j.str("component"))) {
                covered.add(j.str("component"));
            }
            seen.add(j.id());
            seen.add(Fact.makeId("service", "batch-job", j.id()));
        }
        List<String> todo = new ArrayList<>(seen);
        while (!todo.isEmpty()) {
            String node = todo.remove(todo.size() - 1);
            if (owner.containsKey(node)) {
                covered.add(owner.get(node));
            }
            for (String t : runs.getOrDefault(node, List.of())) {
                if (seen.add(t)) {
                    todo.add(t);
                }
            }
        }
        return covered;
    }

    /**
     * Batch work no scheduler definition covers, per component (decisions 116 and 168): each
     * entry point of a batch component is one batch (a top-level script, a C or COBOL program or
     * a Java main no script starts). A batch component with no entry point found is still one
     * batch, unless an entry point of another component runs it. Component name -> entry points
     * (empty list: the component is the batch).
     */
    public static Map<String, List<Map<String, Object>>> unscheduledBatchEntries(Facts facts) {
        List<FileRecord> records = facts.inventory().stream().filter(r -> has(r.component)).toList();
        Map<String, String> owner = new HashMap<>();
        for (FileRecord r : facts.inventory()) {
            if (has(r.component)) {
                owner.put(r.id, r.component);
            }
        }
        for (String kind : List.of("services", "entry_points")) {
            for (Doc d : facts.of(kind)) {
                if (has(d.str("component"))) {
                    owner.put(d.id(), d.str("component"));
                }
            }
        }
        Map<String, List<String>> runs = new HashMap<>();
        Set<String> called = new HashSet<>();
        for (Doc e : facts.of("dependencies")) {
            if (e.bool("resolved") && RUN_EDGES.contains(e.str("kind"))) {
                runs.computeIfAbsent(e.str("source"), k -> new ArrayList<>()).add(e.str("target"));
                called.add(e.str("target"));
            }
        }
        Set<String> covered = covered(facts, runs, owner);
        Map<String, Set<String>> roles = new HashMap<>();
        Set<String> batch = new HashSet<>();
        Set<String> grouped = new HashSet<>();
        for (Component c : facts.components()) {
            Set<String> r = new HashSet<>(c.roles);
            r.add(c.type.value());
            roles.put(c.name, r);
            if (r.contains("batch") && !covered.contains(c.name)) {
                batch.add(c.name);
            }
            if ("grouped".equals(c.detectedBy)) {
                grouped.add(c.name);
            }
        }
        Map<String, String> fileId = new HashMap<>();
        records.forEach(r -> fileId.put(r.path, r.id));
        // In a component grouped from several folders (decision 176), only the scripts that do
        // batch work count: a deploy or admin script of a shared folder is not a batch.
        Set<String> batchy = new HashSet<>();
        if (!grouped.isEmpty()) {
            Map<String, Set<String>> marked = Classify.batchScripts(facts.entities(), records);
            grouped.forEach(name -> batchy.addAll(marked.getOrDefault(name, Set.of())));
        }
        Map<String, List<Map<String, Object>>> entries = new HashMap<>();
        batch.forEach(name -> entries.put(name, new ArrayList<>()));
        Set<String> takenFiles = new HashSet<>();
        List<FileRecord> byPath = new ArrayList<>(records);
        byPath.sort((a, b) -> PyJson.compareCodePoints(a.path, b.path));
        for (FileRecord r : byPath) {
            String comp = r.component;
            if (!"shell".equals(r.language) || !batch.contains(comp) || called.contains(r.id)) {
                continue;
            }
            if (roles.get(comp).stream().anyMatch(ONLINE_ROLES::contains) && !Kinds.inBatchFolder(r.path)) {
                continue; // deploy and admin scripts of an online application
            }
            if (Kinds.inSharedFolder(r.path)) {
                continue; // a helper the batches share
            }
            if (grouped.contains(comp) && !batchy.contains(r.path)) {
                continue;
            }
            entries.get(comp).add(item("id", r.id, "name", r.path.substring(r.path.lastIndexOf('/') + 1), "kind", "script", "file", r.path,
                    "line", null, "component", comp));
            takenFiles.add(r.path);
        }
        List<Doc> services = new ArrayList<>(facts.of("services"));
        // Files something runs: a program started by a script is a step, wherever the script is.
        Map<String, String> fileOf = new HashMap<>();
        records.forEach(r -> fileOf.put(r.id, r.path));
        for (Doc s : services) {
            Evidence e = firstEvidence(s);
            if (e != null) {
                fileOf.put(s.id(), e.file());
            }
        }
        Set<String> runFiles = new HashSet<>();
        called.forEach(t -> {
            if (fileOf.containsKey(t)) {
                runFiles.add(fileOf.get(t));
            }
        });
        services.sort((a, b) -> PyJson.compareCodePoints(a.str("name"), b.str("name")));
        for (Doc svc : services) {
            String comp = svc.str("component") == null ? "" : svc.str("component");
            if (!PROGRAM_SERVICES.contains(svc.str("kind")) || !batch.contains(comp) || called.contains(svc.id())) {
                continue;
            }
            Evidence e = firstEvidence(svc);
            String sfile = e == null ? null : e.file();
            if (has(sfile) && (takenFiles.contains(sfile) || runFiles.contains(sfile))) {
                continue;
            }
            entries.get(comp).add(item("id", svc.id(), "name", svc.str("name"), "kind", "program", "file", sfile, "line",
                    e == null || e.line() == null ? null : (long) e.line(), "component", comp, "service_id", svc.id()));
            if (has(sfile)) {
                takenFiles.add(sfile);
            }
        }
        List<Doc> eps = new ArrayList<>(facts.of("entry_points"));
        eps.sort((a, b) -> PyJson.compareCodePoints(a.str("name"), b.str("name")));
        for (Doc ep : eps) {
            String comp = ep.str("component") == null ? "" : ep.str("component");
            if (!PROGRAM_ENTRY.contains(ep.str("kind")) || !batch.contains(comp) || called.contains(ep.id())) {
                continue;
            }
            if ("main".equals(ep.str("kind")) && roles.get(comp).contains("desktop")) {
                continue; // the main() of a desktop application opens its windows
            }
            String file = ep.str("file");
            if (takenFiles.contains(file) || runFiles.contains(file)) {
                continue;
            }
            String name = ep.str("name");
            entries.get(comp).add(item("id", ep.id(), "name", name.substring(name.lastIndexOf('.') + 1), "kind", "program", "file", file,
                    "line", ep.get("line"), "component", comp));
            takenFiles.add(file);
        }

        // Components an entry point of another component reaches are steps of that batch.
        Set<String> reached = new HashSet<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : entries.entrySet()) {
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> i : e.getValue()) {
                seen.add((String) i.get("id"));
                if (fileId.containsKey((String) i.get("file"))) {
                    seen.add(fileId.get((String) i.get("file")));
                }
            }
            List<String> todo = new ArrayList<>(seen);
            while (!todo.isEmpty()) {
                String node = todo.remove(todo.size() - 1);
                String other = owner.get(node);
                if (has(other) && !other.equals(e.getKey())) {
                    reached.add(other);
                }
                for (String t : runs.getOrDefault(node, List.of())) {
                    if (seen.add(t)) {
                        todo.add(t);
                    }
                }
            }
        }
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        entries.keySet().stream().sorted(PyJson::compareCodePoints).forEach(name -> {
            if (!entries.get(name).isEmpty() || !reached.contains(name)) {
                out.put(name, entries.get(name));
            }
        });
        return out;
    }

    /** One batch per entry point; a batch component with none found is one batch. */
    public static int unscheduledCount(Map<String, List<Map<String, Object>>> entries) {
        return entries.values().stream().mapToInt(items -> Math.max(1, items.size())).sum();
    }

    private static Map<String, Object> recommendation(Doc lib) {
        Object rec = lib.get("recommendation");
        if (rec instanceof Doc d) {
            return d.toMap();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = rec instanceof Map<?, ?> x ? (Map<String, Object>) x : null;
        return m;
    }

    /** The headline counts. */
    public static Map<String, Object> counts(Facts facts) {
        List<Doc> services = facts.of("services");
        List<Doc> screens = facts.of("screens");
        List<Doc> sql = facts.of("sql_statements");
        List<Doc> objects = facts.of("db_objects");
        List<Doc> integrations = facts.of("integrations");
        List<Doc> jobs = facts.of("batch_jobs");
        List<Doc> web = facts.of("web_services");
        List<Doc> libs = facts.of("libraries");
        List<Doc> edges = facts.of("dependencies");
        List<Doc> realScreens = screens.stream().filter(s -> !s.bool("partial")).toList();
        List<Doc> countedJobs = jobs.stream().filter(j -> !"box".equals(j.str("kind"))).toList();
        int unscheduled = unscheduledCount(unscheduledBatchEntries(facts));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("services", item("total", (long) services.size(), "by_kind", counter(each(services, s -> s.str("kind")))));
        out.put("screens", item("total", (long) realScreens.size(), "partials", (long) (screens.size() - realScreens.size()),
                "by_technology", counter(each(realScreens, s -> s.str("technology")))));
        Set<Object> fingerprints = new HashSet<>();
        sql.forEach(s -> fingerprints.add(s.get("fingerprint")));
        out.put("sql", item("total", (long) sql.size(), "distinct", (long) fingerprints.size(), "by_type", counter(each(sql, s -> s.str("type"))),
                "by_source", counter(each(sql, s -> s.str("source"))), "dynamic", sql.stream().filter(s -> s.bool("dynamic")).count(),
                "not_parsed", sql.stream().filter(s -> !s.bool("parsed")).count()));
        out.put("stored_procedures", objects.stream().filter(o -> PROCEDURE_TYPES.contains(o.str("object_type"))).count());
        out.put("db_objects", counter(each(objects, o -> o.str("object_type"))));
        out.put("integrations", item("total", (long) integrations.size(), "by_type", counter(each(integrations, i -> i.str("type")))));
        out.put("web_services", item("total", (long) web.size(), "offered", web.stream().filter(w -> "offered".equals(w.str("side"))).count(),
                "called", web.stream().filter(w -> "called".equals(w.str("side"))).count(),
                "by_style", counter(each(web, w -> w.str("style") + " " + w.str("side"))),
                "operations", web.stream().mapToLong(w -> w.list("operations").size()).sum()));
        out.put("batch_jobs", item("total", (long) (countedJobs.size() + unscheduled), "scheduled", (long) countedJobs.size(),
                "unscheduled", (long) unscheduled, "boxes", (long) (jobs.size() - countedJobs.size()),
                "by_scheduler", counter(each(countedJobs, j -> j.str("scheduler")))));
        out.put("libraries", item("total", (long) libs.size(), "declared", libs.stream().filter(l -> l.bool("declared")).count(),
                "undeclared", libs.stream().filter(l -> !l.bool("declared")).count()));
        out.put("entry_points", counter(each(facts.of("entry_points"), e -> e.str("kind"))));
        out.put("dependencies", item("resolved", edges.stream().filter(e -> e.bool("resolved")).count(),
                "unresolved", edges.stream().filter(e -> !e.bool("resolved")).count()));
        out.put("findings", counter(each(facts.findings(), Finding::ruleId)));
        out.put("findings_by_severity", counter(each(facts.findings(), f -> f.severity().value())));
        out.put("pg_grades", counter(sql.stream().map(s -> s.str("pg_grade")).filter(Summary::has).toList()));
        out.put("complexity", item("services", counter(services.stream().map(s -> s.str("complexity")).filter(Summary::has).toList()),
                "screens", counter(realScreens.stream().map(s -> s.str("complexity")).filter(Summary::has).toList()),
                "batch_jobs", counter(countedJobs.stream().map(s -> s.str("complexity")).filter(Summary::has).toList())));
        Set<Object> cves = new HashSet<>();
        libs.forEach(l -> cves.addAll(l.list("cves")));
        out.put("vulnerable_libraries", item("total", libs.stream().filter(l -> !l.list("cves").isEmpty()).count(),
                "by_severity", counter(libs.stream().map(l -> l.str("max_severity")).filter(Summary::has).toList()),
                "vulnerabilities", (long) cves.size()));
        out.put("eol_libraries", libs.stream().filter(l -> Boolean.TRUE.equals(l.get("eol"))).count());
        out.put("recommendations", counter(libs.stream().map(Summary::recommendation).filter(r -> r != null)
                .map(r -> String.valueOf(r.get("action"))).toList()));
        return out;
    }

    private static Map<String, Object> by(List<FileRecord> records, Function<FileRecord, String> key) {
        Map<String, long[]> out = new TreeMap<>(PyJson::compareCodePoints);
        for (FileRecord r : records) {
            long[] row = out.computeIfAbsent(key.apply(r), k -> new long[4]);
            row[0]++;
            row[1] += r.locCode;
            row[2] += r.locComment;
            row[3] += r.locBlank;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        out.forEach((k, row) -> m.put(k, item("files", row[0], "loc_code", row[1], "loc_comment", row[2], "loc_blank", row[3])));
        return m;
    }

    private static <T> List<T> sorted(Collection<T> items, Function<T, Object> key) {
        List<T> out = new ArrayList<>(items);
        out.sort(PyOrder.by(key));
        return out;
    }

    private static <T> List<T> head(List<T> items, int n) {
        return items.subList(0, Math.min(n, items.size()));
    }

    private static String headCodePoints(String s, int n) {
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** {@code audit-summary.json} of a run. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> build(RunInfo run, Facts facts, Map<String, Object> effort) {
        int n = 15;
        Map<String, Object> summary = build(run, facts, n, effort);
        while (PyJson.dumps(summary, false).length() > MAX_BYTES && n > 1) {
            n /= 2;
            summary = build(run, facts, n, effort);
        }
        if (PyJson.dumps(summary, false).length() > MAX_BYTES) {
            Map<String, Object> lang = (Map<String, Object>) summary.get("by_language");
            Map<String, Object> cut = new LinkedHashMap<>();
            lang.entrySet().stream().limit(20).forEach(e -> cut.put(e.getKey(), e.getValue()));
            summary.put("by_language", cut);
        }
        return summary;
    }

    private static Map<String, Object> build(RunInfo run, Facts facts, int n, Map<String, Object> effort) {
        List<FileRecord> files = facts.inventory();
        List<FileRecord> source = files.stream().filter(f -> f.origin == Origin.SOURCE).toList();
        List<Doc> tables = facts.of("db_objects").stream().filter(o -> "table".equals(o.str("object_type"))).toList();
        Map<String, List<Map<String, Object>>> entries = unscheduledBatchEntries(facts);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", item("run_id", run.runId, "application", run.application, "tool_version", run.toolVersion, "started_at", run.startedAt,
                "sources", run.sources.stream().map(s -> (Object) s.toMap()).toList()));
        out.put("target", run.target);
        Map<String, Object> totals = item("files", (long) source.size(), "input_files", (long) (files.size() - source.size()),
                "loc_code", source.stream().mapToLong(f -> f.locCode).sum(), "loc_comment", source.stream().mapToLong(f -> f.locComment).sum(),
                "loc_blank", source.stream().mapToLong(f -> f.locBlank).sum(), "components", (long) facts.components().size());
        for (String e : ENTITIES) {
            if (!e.equals("inventory")) {
                totals.put(e, (long) facts.size(e));
            }
        }
        out.put("totals", totals);
        out.put("counts", counts(facts));
        out.put("unscheduled_batches", new ArrayList<>(entries.keySet()));
        out.put("unscheduled_batch_entries", entries);
        out.put("tech_stack", head(facts.of("tech_stack"), n * 4).stream()
                .map(t -> (Object) item("name", t.get("name"), "version", t.get("version"), "category", t.get("category"), "eol", t.get("eol")))
                .toList());
        out.put("by_language", by(source, f -> f.language));
        out.put("by_category", by(source, f -> f.category.value()));
        out.put("components", head(sorted(facts.components(), c -> List.of(-(long) c.locCode, c.name)), n).stream()
                .map(c -> (Object) item("name", c.name, "type", c.type.value(), "files", (long) c.files, "loc_code", (long) c.locCode)).toList());
        out.put("largest_files", head(sorted(source, f -> List.of(-(long) f.locCode, f.path)), n).stream()
                .map(f -> (Object) item("path", f.path, "language", f.language, "loc_code", (long) f.locCode)).toList());
        out.put("top_services", head(sorted(facts.of("services"), s -> List.of(-(long) s.list("tables").size(), s.str("name"))), n).stream()
                .map(s -> (Object) item("name", s.str("name"), "kind", s.str("kind"), "component", s.str("component"), "tables",
                        (long) s.list("tables").size()))
                .toList());
        out.put("top_tables", head(sorted(tables, t -> List.of(-(long) t.list("referenced_by").size(), t.str("name"))), n).stream()
                .map(t -> {
                    List<String> crud = new ArrayList<>();
                    Object raw = t.get("crud"); // sorted(t.crud): the keys of a map, the items of a list
                    (raw instanceof Map<?, ?> m ? m.keySet() : raw instanceof Collection<?> c ? c : List.of())
                            .forEach(c -> crud.add(String.valueOf(c)));
                    crud.sort(PyJson::compareCodePoints);
                    return (Object) item("name", t.str("name"), "references", (long) t.list("referenced_by").size(), "crud", crud);
                }).toList());
        out.put("integrations", head(sorted(facts.of("integrations"), i -> List.of(-((Number) i.get("usages")).longValue(), i.str("type"),
                i.str("target"))), n).stream()
                .map(i -> (Object) item("type", i.get("type"), "purpose", i.get("purpose"), "target", i.get("target"), "direction",
                        i.get("direction"), "usages", i.get("usages"), "endpoints", i.get("endpoints")))
                .toList());
        out.put("web_services", head(sorted(facts.of("web_services"), w -> List.of(!"offered".equals(w.str("side")),
                -(long) w.list("operations").size(), w.str("name"))), n).stream()
                .map(w -> (Object) item("name", w.get("name"), "style", w.get("style"), "side", w.get("side"), "url", w.get("url"),
                        "operations", (long) w.list("operations").size(), "component", w.get("component")))
                .toList());
        out.put("top_findings", head(facts.findings(), n).stream()
                .map(f -> (Object) item("rule", f.ruleId(), "severity", f.severity().value(), "file", f.file(), "line",
                        f.line() == null ? null : (long) f.line(), "message", headCodePoints(f.message(), 200)))
                .toList());
        List<Doc> risky = facts.of("libraries").stream().filter(l -> !l.list("cves").isEmpty() || Boolean.TRUE.equals(l.get("eol"))).toList();
        out.put("risky_libraries", head(sorted(risky, l -> List.of((long) SEVERITY_RANK.getOrDefault(
                has(l.str("max_severity")) ? l.str("max_severity") : "unknown", 5), -(long) l.list("cves").size(), l.str("artifact"))), n)
                .stream().map(l -> {
                    Map<String, Object> rec = recommendation(l);
                    return (Object) item("name", has(l.str("group")) ? l.str("group") + ":" + l.str("artifact") : l.str("artifact"),
                            "version", l.get("version"), "max_severity", l.get("max_severity"), "cves", (long) l.list("cves").size(),
                            "eol", l.get("eol"), "recommendation", rec == null ? null : rec.get("action"), "target",
                            rec == null ? null : rec.get("target"));
                }).toList());
        out.put("component_metrics", head(facts.of("metrics"), n * 2).stream()
                .map(m -> (Object) item("component", m.get("component"), "complexity", m.get("complexity"), "complexity_avg",
                        m.get("complexity_avg"), "duplication_pct", m.get("duplication_pct"), "test_ratio", m.get("test_ratio"),
                        "dead_code_candidates", m.get("dead_code_candidates")))
                .toList());
        Map<String, Object> eff = null;
        if (effort != null) {
            eff = new LinkedHashMap<>();
            for (String k : List.of("label", "unit", "low", "likely", "high")) {
                eff.put(k, effort.get(k));
            }
        }
        out.put("effort", eff);
        out.put("vulnerability_sources", run.vulnerabilitySources);
        Map<String, Object> coverage = new LinkedHashMap<>();
        facts.coverage().forEach(c -> coverage.put(c.kind(), c.line()));
        out.put("coverage", coverage);
        Map<String, Object> analyzers = new LinkedHashMap<>();
        run.analyzers.forEach((name, a) -> {
            Map<?, ?> m = (Map<?, ?>) a;
            analyzers.put(name, item("files", m.get("files"), "coverage_pct", m.get("coverage_pct")));
        });
        out.put("analyzer_coverage", analyzers);
        out.put("unresolved_references", (long) run.unresolvedReferences);
        out.put("counting_rules", run.countingRules);
        return out;
    }
}
