package com.legacyaudit.engine.scan;

import com.legacyaudit.engine.metrics.Compute;
import com.legacyaudit.engine.metrics.Effort;
import com.legacyaudit.engine.pg.Grader;
import com.legacyaudit.engine.rules.Findings;
import com.legacyaudit.engine.rules.Packs;
import com.legacyaudit.engine.rules.Targets;
import com.legacyaudit.engine.security.Eol;
import com.legacyaudit.engine.security.Jdk;
import com.legacyaudit.engine.security.Sbom;
import com.legacyaudit.engine.security.Upgrades;
import com.legacyaudit.engine.security.Vulns;
import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.CoverageStatus;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Finding;
import com.legacyaudit.model.facts.InputCoverage;
import com.legacyaudit.model.facts.Origin;
import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The assess stage: PostgreSQL grades, dependency security, rules, findings and effort.
 *
 * <p>It runs every scan after linking and metrics, on the linked facts and the cached per-file
 * results, so a changed weight, severity or disabled rule takes effect without re-analysing files.
 */
final class Assess {

    static final String SECURITY_INPUT = "security";
    static final Map<String, String> TOOL_LABELS = Map.of(Vulns.DC, "OWASP Dependency-Check", Vulns.OSV, "OSV-Scanner");
    private static final Logger LOG = Logger.getLogger("legacy_audit");

    private Assess() {}

    /** What the stage produced; {@code effort} is null for an audit-only run or with effort switched off. */
    record Assessment(List<Finding> findings, Map<String, Object> effort, Map<String, Object> sbom, List<Doc> sources,
            List<InputCoverage> coverage, List<Object> targetMapping, int unmatchedReports) {}

    /** Everything the stage reads. */
    record Input(LoadedConfig loaded, Path teamDir, String runId, String timestamp, String toolVersion, Path runDir,
            List<FileRecord> records, Map<String, List<Doc>> entities, List<Fact> raised, Compute.Result measured,
            List<Findings.Hit> hits, List<Path> scanDirs, Map<String, Path> absPaths) {}

    /** Compiled files (not archives), with {@code has_source} when a source file has the same stem. */
    static List<Map<String, Object>> binaries(List<FileRecord> records) {
        Set<String> stems = new HashSet<>();
        for (FileRecord r : records) {
            if (r.origin == Origin.SOURCE && r.category != Category.BINARY) {
                stems.add(stem(r.path));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (FileRecord r : records) {
            if (r.category == Category.BINARY && !"archive".equals(r.language)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id);
                m.put("path", r.path);
                m.put("file", r.path);
                m.put("component", r.component);
                m.put("has_source", stems.contains(stem(r.path)));
                out.add(m);
            }
        }
        return out;
    }

    /** PurePosixPath(p).stem, lower-cased. */
    private static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    /** One item per distinct statement text, located at its first occurrence. */
    static List<Map<String, Object>> sqlDistinct(List<Doc> statements) {
        Map<List<String>, List<Doc>> groups = new LinkedHashMap<>();
        for (Doc st : statements) {
            groups.computeIfAbsent(java.util.Arrays.asList(st.str("text"), st.str("dialect")), k -> new ArrayList<>()).add(st);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Doc> group : groups.values()) {
            Map<String, Object> item = group.get(0).toMap();
            item.put("occurrences", (long) group.size());
            List<Object> ev = new ArrayList<>();
            for (Doc st : group) {
                for (Object e : st.list("evidence")) {
                    if (ev.size() < 5) {
                        ev.add(e instanceof Evidence x ? x.toMap() : e);
                    }
                }
            }
            item.put("evidence", ev);
            out.add(item);
        }
        return out;
    }

    private static Map<String, Object> libraryItem(Doc lib, List<String> risky) {
        Map<String, Object> item = lib.toMap();
        String lic = lib.str("license") == null ? "" : lib.str("license").toUpperCase(Locale.ROOT);
        item.put("license_risky", risky.stream().anyMatch(r -> lic.contains(r.toUpperCase(Locale.ROOT))));
        item.put("vulnerability_count", (long) lib.list("vulnerabilities").size());
        List<String> cves = new ArrayList<>();
        lib.list("cves").forEach(c -> cves.add(String.valueOf(c)));
        item.put("cves", String.join(", ", cves.subList(0, Math.min(5, cves.size())))
                + (cves.size() > 5 ? " and " + (cves.size() - 5) + " more" : ""));
        item.put("cve_list", cves);
        return item;
    }

    /** Reports dropped in {@code audit-inputs/security/}, by tool. */
    static Map<String, List<Vulns.Report>> securityReports(Path inputsDir) {
        Map<String, List<Vulns.Report>> found = new LinkedHashMap<>();
        found.put(Vulns.DC, new ArrayList<>());
        found.put(Vulns.OSV, new ArrayList<>());
        Path folder = inputsDir.resolve(SECURITY_INPUT);
        if (Files.isDirectory(folder)) {
            List<Path> files;
            try (Stream<Path> s = Files.walk(folder)) {
                files = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                        .sorted((a, b) -> comparePaths(folder.relativize(a), folder.relativize(b))).toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (Path p : files) {
                String display = "audit-inputs/" + SECURITY_INPUT + "/" + folder.relativize(p).toString().replace('\\', '/');
                Vulns.Report report = Vulns.loadReport(p, display);
                if (report != null) {
                    found.get(report.tool).add(report);
                }
            }
        }
        return found;
    }

    /** Python's Path ordering: part by part. */
    private static int comparePaths(Path a, Path b) {
        int n = Math.min(a.getNameCount(), b.getNameCount());
        for (int i = 0; i < n; i++) {
            int c = PyJson.compareCodePoints(a.getName(i).toString(), b.getName(i).toString());
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.getNameCount(), b.getNameCount());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(AuditConfig cfg, String key) {
        Object v = cfg.setting("security", key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Vulns.ToolConfig toolConfig(Map<String, Object> m) {
        Object minutes = m.get("timeout_minutes");
        return new Vulns.ToolConfig(Boolean.TRUE.equals(m.get("enabled")), (String) m.get("path"), (String) m.get("data_dir"),
                (String) m.get("nvd_mirror_url"), Boolean.TRUE.equals(m.get("offline")), minutes instanceof Number n ? n.intValue() : 15);
    }

    private record Vulnerabilities(List<Doc> sources, List<InputCoverage> coverage, int unmatched) {}

    private static Vulnerabilities vulnerabilities(AuditConfig cfg, Path inputsDir, List<Path> scanDirs, Path runDir, List<Doc> libraries) {
        Map<String, List<Vulns.Report>> imported = securityReports(inputsDir);
        List<Doc> sources = new ArrayList<>();
        List<Vulns.Report> reports = new ArrayList<>();
        Path outDir = runDir.resolve("security");
        for (String tool : List.of(Vulns.DC, Vulns.OSV)) {
            List<Vulns.Report> mine = imported.get(tool);
            if (!mine.isEmpty()) {
                String date = mine.stream().map(r -> r.databaseDate).filter(d -> d != null && !d.isEmpty())
                        .max(PyJson::compareCodePoints).orElse(null);
                sources.add(Vulns.source(tool, "imported", null, date, mine.stream().map(r -> r.path).toList()));
                reports.addAll(mine);
                continue;
            }
            Vulns.ToolConfig tc = toolConfig(section(cfg, tool.equals(Vulns.DC) ? "dependency_check" : "osv_scanner"));
            if (!tc.enabled()) {
                sources.add(Vulns.source(tool, "disabled", null, null, List.of()));
                continue;
            }
            if (libraries.isEmpty()) {
                sources.add(Vulns.source(tool, "skipped", "no libraries found", null, List.of()));
                continue;
            }
            Vulns.Outcome outcome = tool.equals(Vulns.DC) ? Vulns.runDependencyCheck(tc, scanDirs, outDir, cfg.application().name())
                    : Vulns.runOsvScanner(tc, scanDirs, outDir);
            sources.add(outcome.source());
            if (outcome.report() != null) {
                reports.add(outcome.report());
            }
        }
        int unmatched = Vulns.applyReports(libraries, reports);

        List<InputCoverage> coverage = new ArrayList<>();
        boolean anyOk = false;
        for (Doc s : sources) {
            String status = s.str("status");
            if (status.equals("skipped")) {
                continue;
            }
            boolean ok = status.equals("ran") || status.equals("imported");
            anyOk |= ok;
            String detail = s.str("detail") != null && !s.str("detail").isEmpty() ? s.str("detail") : status;
            List<String> paths = new ArrayList<>();
            s.list("reports").forEach(r -> paths.add(String.valueOf(r)));
            String name = s.str("name");
            coverage.add(new InputCoverage(Fact.makeId("input_coverage", name),
                    paths.stream().filter(r -> r.startsWith("audit-inputs/")).map(Evidence::of).toList(), name,
                    TOOL_LABELS.get(name) + (ok ? "" : " (" + detail + ")"), ok ? CoverageStatus.PRESENT : CoverageStatus.MISSING,
                    paths.isEmpty() ? name : String.join(", ", paths), paths.size()));
        }
        if (!libraries.isEmpty() && !anyOk) {
            LOG.warning("Vulnerability check not done: no Dependency-Check or OSV-Scanner results");
        }
        return new Vulnerabilities(sources, coverage, unmatched);
    }

    @SuppressWarnings("unchecked")
    static Assessment run(Input in) {
        AuditConfig cfg = in.loaded().config();
        Path tdir = in.teamDir();
        String profile = cfg.target().profile();
        Map<String, Packs.Pack> packs = new LinkedHashMap<>();
        for (String name : cfg.rulePacks()) {
            packs.put(name, Packs.load(name, tdir));
        }
        Map<String, Object> mapping = Targets.resolve(Packs.load("target-mapping", tdir).data, profile);
        Map<String, String> phrases = new LinkedHashMap<>();
        if (mapping.get("phrases") instanceof Map<?, ?> ph) {
            ph.forEach((k, v) -> phrases.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        Map<String, List<Doc>> entities = in.entities();
        List<Doc> statements = entities.getOrDefault("sql_statements", List.of());
        if (!Targets.NONE.equals(profile)) { // audit only: no target database to grade against
            Grader.gradeAll(statements, entities.getOrDefault("db_objects", List.of()), Packs.load("postgresql", tdir).data);
        }

        List<Doc> libraries = entities.getOrDefault("libraries", List.of());
        Eol.apply(libraries, entities.getOrDefault("tech_stack", List.of()), Targets.resolve(Packs.load("eol", tdir).data, profile));
        Vulnerabilities vulns = vulnerabilities(cfg, in.loaded().inputsDir(), in.scanDirs(), in.runDir(), libraries);
        Map<String, Object> dependency = Targets.resolve(Packs.load("dependency", tdir).data, profile);
        Upgrades.apply(libraries, dependency);
        List<String> risky = new ArrayList<>();
        if (dependency.get("licenses") instanceof Map<?, ?> lic && lic.get("risky") instanceof List<?> r) {
            r.forEach(x -> risky.add(String.valueOf(x)));
        }

        List<Map.Entry<String, Path>> jars = new ArrayList<>();
        for (FileRecord r : in.records()) {
            if ("archive".equals(r.language) && r.path.toLowerCase(Locale.ROOT).endsWith(".jar") && in.absPaths().containsKey(r.path)) {
                jars.add(Map.entry(r.path, in.absPaths().get(r.path)));
            }
        }
        jars.sort((a, b) -> PyJson.compareCodePoints(a.getKey(), b.getKey()));
        Map<String, Object> jdkCfg = section(cfg, "jdk_tools");
        Jdk.Result jdk = Jdk.run(Boolean.TRUE.equals(jdkCfg.get("enabled")), (String) jdkCfg.get("java_home"), jars);
        if (!jdk.status().equals("disabled")) {
            LOG.info("JDK tools (jdeps/jdeprscan): " + jdk.status() + ", " + jdk.items().size() + " items");
        }

        Map<String, Object> sbom = Sbom.build(cfg.application().name(), in.runId(), in.timestamp(), in.toolVersion(), libraries);

        Map<String, List<Map<String, Object>>> cache = new HashMap<>();
        Map<String, Supplier<List<Map<String, Object>>>> extra = new HashMap<>();
        extra.put("units", () -> in.measured().units);
        extra.put("dead_code", () -> in.measured().deadCode);
        extra.put("binaries", () -> binaries(in.records()));
        extra.put("sql_distinct", () -> sqlDistinct(statements));
        extra.put("jdk_findings", jdk::items);
        extra.put("libraries", () -> libraries.stream().map(l -> libraryItem(l, risky)).toList());
        extra.put("metrics", () -> in.measured().metrics.stream().map(Doc::toMap).toList());
        extra.put("inventory", () -> in.records().stream().map(FileRecord::toMap).toList());
        Function<String, List<Map<String, Object>>> collection = name -> cache.computeIfAbsent(name, n -> extra.containsKey(n)
                ? extra.get(n).get() : entities.getOrDefault(n, List.of()).stream().map(Doc::toMap).toList());

        List<Packs.Rule> rules = new ArrayList<>();
        packs.values().forEach(p -> rules.addAll(p.rules));
        List<Finding> all = new ArrayList<>();
        in.raised().forEach(f -> all.add(Findings.of(f)));
        all.addAll(Findings.evaluate(rules, collection, in.hits()));
        Set<String> disabled = new HashSet<>();
        if (cfg.setting("rules", "disabled") instanceof List<?> d) {
            d.forEach(x -> disabled.add(String.valueOf(x)));
        }
        Map<String, String> severity = new HashMap<>();
        if (cfg.setting("rules", "severity") instanceof Map<?, ?> s) {
            s.forEach((k, v) -> severity.put(String.valueOf(k), String.valueOf(v)));
        }
        List<Finding> findings = new ArrayList<>();
        for (Finding f : Findings.applyConfig(all, disabled, severity)) { // [[phrase]] placeholders take the target's wording
            String message = Targets.fill(f.message(), phrases);
            findings.add(new Finding(f.id(), f.evidence(), f.ruleId(), f.severity(), f.category(), f.file(), f.line(),
                    message == null ? "" : message, Targets.fill(f.remediation(), phrases)));
        }

        Map<String, String> componentOf = new HashMap<>();
        in.records().forEach(r -> componentOf.put(r.path, r.component));
        Object effortOn = cfg.setting("effort", "enabled");
        boolean planEffort = (effortOn == null || Boolean.TRUE.equals(effortOn)) && !Targets.NONE.equals(profile);
        Map<String, Object> effort = planEffort
                ? Effort.estimate(Effort.weights(cfg.setting("effort", "weights")), entities, componentOf) : null;
        List<Object> targets = mapping.get("targets") instanceof List<?> t ? new ArrayList<>(t) : new ArrayList<>();
        return new Assessment(findings, effort, sbom, vulns.sources(), vulns.coverage(), targets, vulns.unmatched());
    }
}
