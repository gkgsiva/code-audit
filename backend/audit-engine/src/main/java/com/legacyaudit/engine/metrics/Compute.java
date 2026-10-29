package com.legacyaudit.engine.metrics;

import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Component;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Origin;
import com.legacyaudit.model.facts.Schemas;
import com.legacyaudit.model.json.PyNum;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cross-file metrics, recomputed every run from cached per-file results: duplication,
 * fan-in/fan-out, complexity scores and bands, dead-code candidates and per-component metrics.
 *
 * <p>Score of an item = sum over features of {@code weight x feature / scale} ({@code complexity}
 * in {@code audit.yaml}). Features:
 *
 * <ul>
 *   <li>services: {@code loc} and {@code cyclomatic} of the code the service runs (its handler, or
 *       its whole code file, plus code files reached within two hops over calls/uses/invokes/runs/
 *       triggers/includes edges), {@code tables} and {@code integrations} it reaches,
 *       {@code vendor_sql} (statements with vendor-specific features in that code) and
 *       {@code fan_out};
 *   <li>screens: {@code loc} of the page, {@code fields} and {@code fan_out};
 *   <li>batch jobs: the score of the job's {@code batch-job} service;
 *   <li>components: the mean score of their services and screens.
 * </ul>
 */
public final class Compute {

    public static final List<String> BANDS = List.of("Simple", "Medium", "Complex", "Very complex");
    static final Set<String> FOOTPRINT_EDGES = Set.of("calls", "uses", "invokes", "runs", "triggers", "includes");
    static final int FOOTPRINT_DEPTH = 2;
    // Files that can be dead code; screens, SQL and JavaScript are judged elsewhere or not at all.
    public static final Set<String> DEAD_CODE_LANGUAGES = Set.of("java", "c", "cpp", "proc", "cobol", "procobol", "shell", "perl", "python");
    public static final Set<String> CODE_LANGUAGES = union(DEAD_CODE_LANGUAGES,
            Set.of("jsp", "sql", "plsql", "javascript", "typescript", "batch", "csharp", "vb6", "vbnet"));

    private Compute() {}

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }

    /** The {@code complexity:} settings of audit.yaml (defaults merged in by the config reader). */
    public record Settings(Map<String, Double> weights, Map<String, Double> scale, Map<String, Double> thresholds, int highUnit) {

        @SuppressWarnings("unchecked")
        public static Settings of(AuditConfig cfg) {
            return new Settings(numbers(cfg.setting("complexity", "weights")), numbers(cfg.setting("complexity", "scale")),
                    numbers(cfg.setting("complexity", "thresholds")), ((Number) cfg.setting("complexity", "high_unit")).intValue());
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Double> numbers(Object m) {
            Map<String, Double> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, ((Number) v).doubleValue()));
            return out;
        }
    }

    /** What the metrics step gives: per-component metrics and the inputs of metric-based rules. */
    public static final class Result {
        public final List<Doc> metrics = new ArrayList<>();
        /** Every unit, with its file, component and whether it is too complex. */
        public final List<Map<String, Object>> units = new ArrayList<>();
        public final List<Map<String, Object>> deadCode = new ArrayList<>();
        /** path -> duplicated lines. */
        public final Map<String, Integer> duplicated = new LinkedHashMap<>();
        /** item id -> features. */
        public final Map<String, Map<String, Double>> features = new LinkedHashMap<>();
    }

    public static String band(double score, Map<String, Double> thresholds) {
        if (score >= thresholds.get("very_complex")) {
            return BANDS.get(3);
        }
        if (score >= thresholds.get("complex")) {
            return BANDS.get(2);
        }
        if (score >= thresholds.get("medium")) {
            return BANDS.get(1);
        }
        return BANDS.get(0);
    }

    public static double score(Map<String, Double> features, Settings cfg) {
        List<Double> parts = new ArrayList<>();
        features.forEach((k, v) -> {
            Double s = cfg.scale().get(k);
            parts.add(cfg.weights().getOrDefault(k, 0.0) * v / (s == null || s == 0.0 ? 1.0 : s));
        });
        return PyNum.round(PyNum.sum(parts), 2);
    }

    static Map<String, Set<Integer>> duplicatedLines(Map<String, List<List<Long>>> windows) {
        Map<Long, List<long[]>> seen = new LinkedHashMap<>();
        Map<long[], String> pathOf = new HashMap<>();
        windows.forEach((path, items) -> {
            for (List<Long> w : items) {
                long[] place = {w.get(1), w.get(2)};
                pathOf.put(place, path);
                seen.computeIfAbsent(w.get(0), k -> new ArrayList<>()).add(place);
            }
        });
        Map<String, Set<Integer>> marked = new LinkedHashMap<>();
        for (List<long[]> places : seen.values()) {
            if (places.size() < 2) {
                continue;
            }
            // Overlapping windows of one repetitive block in the same place are not clones.
            Set<String> starts = new HashSet<>();
            for (long[] p : places) {
                starts.add(pathOf.get(p) + "\u001f" + p[0]);
            }
            if (starts.size() < 2) {
                continue;
            }
            for (long[] p : places) {
                Set<Integer> lines = marked.computeIfAbsent(pathOf.get(p), k -> new HashSet<>());
                for (long i = p[0]; i <= p[1]; i++) {
                    lines.add((int) i);
                }
            }
        }
        return marked;
    }

    private static final class Graph {
        final Map<String, List<Doc>> out = new HashMap<>();
        final Map<String, List<Doc>> into = new HashMap<>();

        Graph(List<Doc> edges) {
            for (Doc e : edges) {
                if (e.bool("resolved")) {
                    out.computeIfAbsent(e.str("source"), k -> new ArrayList<>()).add(e);
                    into.computeIfAbsent(e.str("target"), k -> new ArrayList<>()).add(e);
                }
            }
        }

        Set<String> reach(Collection<String> starts, Set<String> kinds, Integer depth) {
            Set<String> seen = new LinkedHashSet<>(starts);
            List<String> frontier = new ArrayList<>(seen);
            int level = 0;
            while (!frontier.isEmpty() && (depth == null || level < depth)) {
                List<String> next = new ArrayList<>();
                for (String node : frontier) {
                    for (Doc e : out.getOrDefault(node, List.of())) {
                        if ((kinds == null || kinds.contains(e.str("kind"))) && seen.add(e.str("target"))) {
                            next.add(e.str("target"));
                        }
                    }
                }
                frontier = next;
                level++;
            }
            return seen;
        }
    }

    private static int count(Map<String, Integer> m, String key) {
        return m.getOrDefault(key, 0);
    }

    /**
     * Fill complexity, scores and fan-in/out on services, screens and batch jobs (in place) and
     * return per-component metrics plus the inputs of metric-based rules.
     */
    public static Result compute(Settings cfg, List<FileRecord> records, List<Component> components, Map<String, List<Doc>> entities,
            Map<String, List<Doc>> units, Map<String, List<List<Long>>> windows) {
        Map<String, FileRecord> byPath = new HashMap<>();
        Map<String, FileRecord> byId = new HashMap<>();
        for (FileRecord r : records) {
            byPath.put(r.path, r);
            byId.put(r.id, r);
        }
        List<Doc> services = entities.getOrDefault("services", List.of());
        List<Doc> screens = entities.getOrDefault("screens", List.of());
        List<Doc> jobs = entities.getOrDefault("batch_jobs", List.of());
        List<Doc> sql = entities.getOrDefault("sql_statements", List.of());
        List<Doc> entries = entities.getOrDefault("entry_points", List.of());
        Graph graph = new Graph(entities.getOrDefault("dependencies", List.of()));
        Map<String, Double> thresholds = cfg.thresholds();
        Result result = new Result();

        Map<String, Integer> vendorSql = new HashMap<>();
        for (Doc s : sql) {
            if (!s.list("vendor_features").isEmpty()) {
                vendorSql.merge(s.str("file"), 1, Integer::sum);
            }
        }
        Map<String, Integer> servicesPerFile = new HashMap<>();
        for (Doc svc : services) {
            if (svc.str("file") != null && !svc.str("file").isEmpty()) {
                servicesPerFile.merge(svc.str("file"), 1, Integer::sum);
            }
        }
        Map<String, Doc> jobOfService = new HashMap<>();
        for (Doc j : jobs) {
            jobOfService.put(Fact.makeId("service", "batch-job", j.id()), j);
        }

        // ---- services ------------------------------------------------------------------------
        for (Doc svc : services) {
            List<String> nodes = new ArrayList<>(List.of(svc.id()));
            Doc job = jobOfService.get(svc.id());
            if (job != null) {
                nodes.add(job.id());
            }
            String file = svc.str("file");
            FileRecord fileRec = file == null ? null : byPath.get(file);
            String ownFile = fileRec != null && CODE_LANGUAGES.contains(fileRec.language) ? file : null;
            List<Doc> ownUnits = units.getOrDefault(ownFile == null ? "" : ownFile, List.of());
            boolean shared = ownFile != null && count(servicesPerFile, ownFile) > 1;
            String handler = svc.str("handler");
            if (shared && handler != null && !handler.isEmpty()) {
                String method = handler.substring(handler.lastIndexOf('.') + 1);
                method = method.substring(method.lastIndexOf('#') + 1);
                List<Doc> kept = new ArrayList<>();
                for (Doc u : ownUnits) {
                    if (method.equals(u.str("name"))) {
                        kept.add(u);
                    }
                }
                ownUnits = kept;
            }
            String fnode = ownFile != null ? byPath.get(ownFile).id : null;
            List<String> starts = new ArrayList<>(nodes);
            if (fnode != null) {
                starts.add(fnode);
            }
            Set<String> reached = graph.reach(starts, FOOTPRINT_EDGES, FOOTPRINT_DEPTH);
            Set<String> files = new LinkedHashSet<>();
            for (String n : reached) {
                FileRecord r = byId.get(n);
                if (r != null && CODE_LANGUAGES.contains(r.language)) {
                    files.add(r.path);
                }
            }
            files.remove(ownFile == null ? "" : ownFile);
            long ownLoc = 0;
            long ownCc = 0;
            for (Doc u : ownUnits) {
                ownLoc += u.integer("loc");
                ownCc += u.integer("cyclomatic");
            }
            if (ownFile != null && ownUnits.isEmpty() && !shared) {
                ownLoc = byPath.get(ownFile).locCode;
            }
            List<String> outNodes = new ArrayList<>(nodes);
            if (fnode != null && !shared) {
                outNodes.add(fnode);
            }
            Set<String> fanOut = new HashSet<>();
            for (String n : outNodes) {
                for (Doc e : graph.out.getOrDefault(n, List.of())) {
                    fanOut.add(e.str("target"));
                }
            }
            Set<String> fanIn = new HashSet<>();
            for (String n : nodes) {
                for (Doc e : graph.into.getOrDefault(n, List.of())) {
                    fanIn.add(e.str("source"));
                }
            }
            svc.set("fan_out", (long) fanOut.size());
            svc.set("fan_in", (long) fanIn.size());
            long loc = ownLoc;
            long cc = ownCc;
            long vendor = ownFile != null && !shared ? count(vendorSql, ownFile) : 0;
            for (String p : files) {
                loc += byPath.get(p).locCode;
                for (Doc u : units.getOrDefault(p, List.of())) {
                    cc += u.integer("cyclomatic");
                }
                vendor += count(vendorSql, p);
            }
            Map<String, Double> features = new LinkedHashMap<>();
            features.put("loc", (double) loc);
            features.put("cyclomatic", (double) cc);
            features.put("tables", (double) svc.list("tables").size());
            features.put("integrations", (double) svc.list("external_calls").size());
            features.put("vendor_sql", (double) vendor);
            features.put("fan_out", (double) fanOut.size());
            double s = score(features, cfg);
            svc.set("complexity_score", s);
            svc.set("complexity", band(s, thresholds));
            result.features.put(svc.id(), features);
            if (job != null) {
                job.set("complexity_score", s);
                job.set("complexity", svc.str("complexity"));
            }
        }

        // ---- screens --------------------------------------------------------------------------
        Map<String, Integer> screensPerFile = new HashMap<>();
        for (Doc sc : screens) {
            if (sc.str("file") != null && !sc.str("file").isEmpty()) {
                screensPerFile.merge(sc.str("file"), 1, Integer::sum);
            }
        }
        for (Doc sc : screens) {
            if (sc.bool("partial")) {
                continue;
            }
            String file = sc.str("file");
            boolean hasFile = file != null && !file.isEmpty();
            FileRecord rec = byPath.get(file == null ? "" : file);
            int per = hasFile ? count(screensPerFile, file) : 1;
            List<String> nodes = new ArrayList<>(List.of(sc.id()));
            if (rec != null && per == 1) {
                nodes.add(rec.id);
            }
            Set<String> fanOut = new HashSet<>();
            for (String n : nodes) {
                for (Doc e : graph.out.getOrDefault(n, List.of())) {
                    fanOut.add(e.str("target"));
                }
            }
            Map<String, Double> features = new LinkedHashMap<>();
            features.put("loc", rec != null ? PyNum.round((double) rec.locCode / per, 1) : 0.0);
            features.put("fields", (double) sc.integer("fields_count"));
            features.put("fan_out", (double) fanOut.size());
            double s = score(features, cfg);
            sc.set("complexity_score", s);
            sc.set("complexity", band(s, thresholds));
            result.features.put(sc.id(), features);
        }

        // ---- duplication -----------------------------------------------------------------------
        duplicatedLines(windows).forEach((p, lines) -> {
            FileRecord r = byPath.get(p);
            if (r != null) {
                result.duplicated.put(p, Math.min(lines.size(), r.locCode));
            }
        });

        // ---- dead-code candidates --------------------------------------------------------------
        Set<String> roots = new LinkedHashSet<>();
        services.forEach(s -> roots.add(s.id()));
        jobs.forEach(j -> roots.add(j.id()));
        screens.stream().filter(s -> !s.bool("partial")).forEach(s -> roots.add(s.id()));
        List<String> paths = new ArrayList<>();
        services.forEach(s -> paths.add(s.str("file")));
        screens.forEach(s -> paths.add(s.str("file")));
        entries.forEach(e -> paths.add(e.str("file")));
        for (String path : paths) {
            FileRecord r = byPath.get(path == null ? "" : path);
            if (r != null) {
                roots.add(r.id);
            }
        }
        for (FileRecord r : records) {
            if (!CODE_LANGUAGES.contains(r.language) || r.category == Category.CONFIG || r.category == Category.BUILD) {
                roots.add(r.id);
            }
        }
        // The scripts nothing calls in a batch component are where its batch starts (decision 116).
        Set<String> batchComps = new HashSet<>();
        for (Component c : components) {
            if (c.roles.contains("batch")) {
                batchComps.add(c.name);
            }
        }
        Set<String> called = new HashSet<>();
        graph.out.values().forEach(es -> es.forEach(e -> called.add(e.str("target"))));
        for (FileRecord r : records) {
            if (r.category == Category.SCRIPT && batchComps.contains(r.component) && !called.contains(r.id)) {
                roots.add(r.id);
            }
        }
        Set<String> live = graph.reach(roots, null, null);
        for (FileRecord r : records) {
            if (r.origin == Origin.SOURCE && DEAD_CODE_LANGUAGES.contains(r.language) && r.category != Category.TEST
                    && r.category != Category.GENERATED && !live.contains(r.id)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("file", r.path);
                d.put("component", r.component);
                d.put("language", r.language);
                result.deadCode.add(d);
            }
        }

        // ---- units list for rules --------------------------------------------------------------
        for (var e : new TreeMap<>(units).entrySet()) {
            FileRecord rec = byPath.get(e.getKey());
            if (rec == null || rec.category == Category.TEST || rec.category == Category.GENERATED) {
                continue;
            }
            for (Doc u : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>(u.toMap());
                m.put("file", e.getKey());
                m.put("component", rec.component);
                m.put("complex", u.integer("cyclomatic") >= cfg.highUnit());
                result.units.add(m);
            }
        }

        // ---- per component ---------------------------------------------------------------------
        Map<String, List<FileRecord>> members = new HashMap<>();
        for (FileRecord r : records) {
            if (r.component != null && !r.component.isEmpty() && r.origin == Origin.SOURCE) {
                members.computeIfAbsent(r.component, k -> new ArrayList<>()).add(r);
            }
        }
        Map<String, Integer> deadPer = new HashMap<>();
        for (Map<String, Object> d : result.deadCode) {
            deadPer.merge(d.get("component") == null ? "" : (String) d.get("component"), 1, Integer::sum);
        }
        record Item(double score, String band) {}
        Map<String, List<Item>> itemsPer = new HashMap<>();
        for (List<Doc> list : List.of(services, screens)) {
            for (Doc d : list) {
                String comp = d.str("component");
                if (comp != null && !comp.isEmpty() && d.get("complexity_score") != null && d.str("complexity") != null
                        && !d.str("complexity").isEmpty()) {
                    itemsPer.computeIfAbsent(comp, k -> new ArrayList<>())
                            .add(new Item(((Number) d.get("complexity_score")).doubleValue(), d.str("complexity")));
                }
            }
        }
        for (Component c : components) {
            List<FileRecord> compFiles = members.getOrDefault(c.name, List.of());
            int codeFiles = 0;
            List<Long> cc = new ArrayList<>();
            long locCode = 0;
            long locComment = 0;
            long locBlank = 0;
            long dup = 0;
            long tests = 0;
            for (FileRecord f : compFiles) {
                if (CODE_LANGUAGES.contains(f.language) && f.category != Category.TEST) {
                    codeFiles++;
                }
                if (f.category != Category.TEST) {
                    for (Doc u : units.getOrDefault(f.path, List.of())) {
                        cc.add((long) u.integer("cyclomatic"));
                    }
                }
                locCode += f.locCode;
                locComment += f.locComment;
                locBlank += f.locBlank;
                dup += count(result.duplicated, f.path);
                if (f.category == Category.TEST) {
                    tests++;
                }
            }
            List<Item> items = itemsPer.getOrDefault(c.name, List.of());
            Double mean = null;
            if (!items.isEmpty()) {
                mean = PyNum.round(PyNum.sum(items.stream().map(Item::score).toList()) / items.size(), 2);
            }
            long fanInMax = 0;
            long fanOutMax = 0;
            for (Doc s : services) {
                if (c.name.equals(s.str("component"))) {
                    fanInMax = Math.max(fanInMax, ((Number) s.get("fan_in")).longValue());
                    fanOutMax = Math.max(fanOutMax, ((Number) s.get("fan_out")).longValue());
                }
            }
            Map<String, Long> bandCounts = new HashMap<>();
            for (Item it : items) {
                bandCounts.merge(it.band(), 1L, Long::sum);
            }
            Map<String, Object> bands = new LinkedHashMap<>();
            for (String b : BANDS) {
                if (bandCounts.getOrDefault(b, 0L) != 0) {
                    bands.put(b, bandCounts.get(b));
                }
            }
            long ccSum = cc.stream().mapToLong(Long::longValue).sum();
            final long high = cfg.highUnit();
            result.metrics.add(Doc.of(Schemas.METRIC,
                    "id", Fact.makeId("metric", c.id),
                    "evidence", c.evidence,
                    "component", c.name,
                    "loc_code", locCode,
                    "loc_comment", locComment,
                    "loc_blank", locBlank,
                    "files", (long) compFiles.size(),
                    "units", (long) cc.size(),
                    "complexity_avg", cc.isEmpty() ? null : PyNum.round((double) ccSum / cc.size(), 2),
                    "complexity_max", cc.isEmpty() ? null : cc.stream().mapToLong(Long::longValue).max().getAsLong(),
                    "complex_units", cc.stream().filter(v -> v >= high).count(),
                    "duplicated_lines", dup,
                    "duplication_pct", locCode != 0 ? PyNum.round(100.0 * dup / locCode, 1) : null,
                    "test_files", tests,
                    "test_ratio", codeFiles != 0 ? PyNum.round((double) tests / codeFiles, 3) : null,
                    "dead_code_candidates", (long) deadPer.getOrDefault(c.name, 0),
                    "fan_in_max", fanInMax,
                    "fan_out_max", fanOutMax,
                    "complexity", mean != null ? band(mean, thresholds) : null,
                    "complexity_score", mean,
                    "bands", bands));
        }
        return result;
    }
}
