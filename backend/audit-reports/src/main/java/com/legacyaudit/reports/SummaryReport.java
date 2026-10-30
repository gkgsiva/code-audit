package com.legacyaudit.reports;

import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.engine.store.FactWriter;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.reports.jinja.Environment;
import com.legacyaudit.reports.jinja.Values;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code legacy-audit report --type summary}: a five-part summary report as one HTML page.
 *
 * <ol>
 *   <li><b>Project</b>: what the application is, its technology stack, and its modules
 *       (components) with a description, technology, complexity and lines of code;
 *   <li><b>Database and queries</b>: SQL statements, PostgreSQL readiness, tables, databases;
 *   <li><b>Integrations</b>: one row per interface with its purpose and description;
 *   <li><b>Batches</b>: every batch once, with what it does and when it runs ({@link Batches});
 *   <li><b>Dependency vulnerabilities</b>: the known vulnerabilities of the libraries, from OWASP
 *       Dependency-Check and OSV-Scanner merged into one list, each with the scanner(s) that found
 *       it; says plainly when neither scanner's results are there.
 * </ol>
 *
 * <p>Each part has headline figures, charts and the data behind them (tables, and a CSV per table
 * next to the page). Every number comes from the scan; descriptions come from the Copilot
 * enrichment when it exists, else from the scan, and say which. The Python tool also built a
 * PowerPoint of this page; the Java version does not (decision 223).
 */
public final class SummaryReport {

    static final List<List<String>> SECTIONS = List.of(
            List.of("project", "Project", "Everyone", "series-1"),
            List.of("data", "Database and queries", "Architects and DBAs", "series-3"),
            List.of("integrations", "Integrations", "Architects", "series-2"),
            List.of("batch", "Batches", "Operations and architects", "series-7"),
            List.of("security", "Dependency vulnerabilities", "Security and architects", "series-8"));
    static final List<String> WEB_COLUMNS = List.of(
            "Offered / called", "Style", "Web service", "Address", "Operations", "Found through", "Module", "What it is for",
            "Suggested migration");
    static final List<String> COMPLEXITY_ORDER = List.of("Simple", "Medium", "Complex", "Very complex");
    static final List<String> GRADES = List.of("auto", "review", "manual");
    static final Map<String, String> GRADE_TOKEN = Map.of("auto", "good", "review", "warning", "manual", "critical");
    static final List<String> SERIES = List.of("series-1", "series-2", "series-3", "series-4", "series-5", "series-6", "series-7",
            "series-8");
    static final List<String> SEVERITIES = List.of("critical", "high", "medium", "low", "unknown");
    static final Map<String, String> SEVERITY_TOKEN = Map.of(
            "critical", "critical", "high", "serious", "medium", "warning", "low", "series-1", "unknown", "info");
    static final Map<String, String> SCANNERS = new LinkedHashMap<>();
    static final Map<String, String> SCANNER_STATUS = Map.of(
            "imported", "report imported from audit-inputs/security", "ran", "ran during the scan", "missing", "not used",
            "failed", "failed", "disabled", "turned off in audit.yaml", "skipped", "not needed");
    private static final Pattern IDP = Rx.of("Login / authentication with (.+?) \\(");

    static {
        SCANNERS.put("dependency-check", "Dependency-Check");
        SCANNERS.put("osv-scanner", "OSV-Scanner");
    }

    private SummaryReport() {}

    /** One table: its CSV name and anchor, title, columns, rows, a note and the text when empty. */
    public record Table(String key, String title, List<String> columns, List<List<Object>> rows, String note, String empty) {
        Table(String key, String title, List<String> columns, List<List<Object>> rows, String note) {
            this(key, title, columns, rows, note, "None found.");
        }

        Map<String, Object> asMap() {
            return D.of("key", key, "title", title, "columns", columns, "rows", rows, "note", note, "empty", empty);
        }
    }

    /** One chart: bar, doughnut or hbar. */
    public record Chart(String key, String title, String kind, List<?> labels, List<?> values, List<String> colors, String unit) {
        Map<String, Object> asMap() {
            return D.of("key", key, "title", title, "kind", kind, "labels", labels, "values", values, "colors", colors, "unit", unit);
        }
    }

    /** One part of the page. */
    public record Section(String key, String title, String audience, String color, List<String> intro, List<Values.Tuple> kpis,
            List<Chart> charts, List<Table> tables, String source_note, String alert) {
        Map<String, Object> asMap() {
            List<Object> c = new ArrayList<>();
            charts.forEach(x -> c.add(x.asMap()));
            List<Object> t = new ArrayList<>();
            tables.forEach(x -> t.add(x.asMap()));
            return D.of("key", key, "title", title, "audience", audience, "color", color, "intro", intro, "kpis", kpis, "charts", c,
                    "tables", t, "source_note", source_note, "alert", alert);
        }
    }

    /** Numbers as the summary prints them: {@code 1,234}, {@code 12.5}, else {@code str()}. */
    public static String num(Object v) {
        if (v instanceof Double || v instanceof Float) {
            String s = Fmt.fixed(((Number) v).doubleValue(), 1, true);
            s = s.replaceAll("0+$", "");
            return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
        }
        if (v instanceof Boolean b) {
            return b ? "1" : "0";
        }
        if (v instanceof Number n) {
            return String.format(Locale.ROOT, "%,d", n.longValue());
        }
        return Py.str(v);
    }

    static String pct(double part, double whole) {
        return whole != 0 ? (long) Math.rint(100 * part / whole) + "%" : "0%";
    }

    static String plural(long n, String word, String many) {
        return num(n) + " " + (n == 1 ? word : many != null ? many : word + "s");
    }

    static String plural(long n, String word) {
        return plural(n, word, null);
    }

    /** The {@code n} largest counts, the rest as "other". */
    static List<List<Object>> top(Counter counter, int n) {
        List<Map.Entry<String, Long>> items = counter.mostCommon();
        List<Object> labels = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (items.size() > n) {
            long rest = 0;
            for (Map.Entry<String, Long> e : items.subList(n - 1, items.size())) {
                rest += e.getValue();
            }
            items = new ArrayList<>(items.subList(0, n - 1));
            items.add(Map.entry("other", rest));
        }
        items.forEach(e -> {
            labels.add(e.getKey());
            values.add(e.getValue());
        });
        return List.of(labels, values);
    }

    /** End-of-life cell: the date when known, else yes / no / blank. */
    static String eol(Map<String, Object> tech) {
        if (D.t(tech, "eol_date")) {
            return D.str(tech, "eol_date");
        }
        if (D.get(tech, "eol") == null) {
            return "";
        }
        return D.t(tech, "eol") ? "yes" : "no";
    }

    static String planText(Map<String, Object> plan) {
        if (plan == null || plan.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String x : List.of(D.s(plan, "approach"), D.s(plan, "notes"))) {
            if (!x.isEmpty()) {
                parts.add(x);
            }
        }
        return String.join(": ", parts);
    }

    private static int cmp(String a, String b) {
        return PyJson.compareCodePoints(a, b);
    }

    private static List<Object> row(Object... cells) {
        return new ArrayList<>(java.util.Arrays.asList(cells));
    }

    private static Values.Tuple kpi(String label, Object value) {
        return RunData.tuple(label, value);
    }

    /** Python's {@code str.capitalize()}. */
    static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        int first = s.offsetByCodePoints(0, 1);
        return s.substring(0, first).toUpperCase(Locale.ROOT) + s.substring(first).toLowerCase(Locale.ROOT);
    }

    static final class Builder {
        final RunData rd;
        final Map<String, Object> s;
        final Map<String, Object> c;
        final Map<String, Object> e;
        final Map<String, Object> fn;
        final String app;

        Builder(RunData rd, Map<String, Object> enrichment) {
            this.rd = rd;
            this.s = rd.summary;
            this.c = D.map(s, "counts");
            this.e = enrichment;
            this.fn = D.map(enrichment, "functional");
            this.app = D.t(rd.run, "application") ? D.str(rd.run, "application") : "Application";
        }

        private long cnt(String path) {
            return Fmt.n(c, path);
        }

        // ---- 1 project ------------------------------------------------------------------------

        /** (sentences, source): the AI's business summary, else one written from the counts. */
        List<Object> description() {
            if (D.t(fn, "summary")) {
                return List.of(List.of(D.str(fn, "summary")), "AI (Copilot, functional stage)");
            }
            Map<String, Object> nar = D.map(D.map(e, "narratives"), "executive");
            if (D.t(nar, "text")) {
                return List.of(List.of(D.str(nar, "text")), "AI (Copilot, narratives stage)");
            }
            Map<String, Object> ov = D.map(e, "overview");
            if (D.t(ov, "purpose")) {
                return List.of(List.of(D.str(ov, "purpose")), "AI (Copilot, overview stage)");
            }
            Map<String, Object> t = D.map(s, "totals");
            List<Values.Tuple> langs = new ArrayList<>();
            D.map(s, "by_language").forEach((k, v) -> langs.add(RunData.tuple(k, D.get(v, "loc_code", 0L))));
            langs.sort((a, b) -> Double.compare(D.num(b.get(1)), D.num(a.get(1))));
            List<String> main = new ArrayList<>();
            for (Values.Tuple kv : langs) {
                if (Py.truthy(kv.get(1)) && main.size() < 3) {
                    main.add(Py.str(kv.get(0)));
                }
            }
            Counter types = new Counter();
            rd.items("components").forEach(x -> types.add(Py.str(x.get("type"))));
            List<String> kindParts = new ArrayList<>();
            types.mostCommon(4).forEach(en -> kindParts.add(plural(en.getValue(), en.getKey())));
            String first = app + " has " + plural(D.i(t, "components"), "module") + " (" + String.join(", ", kindParts) + ") and "
                    + num(D.get(t, "loc_code", 0L)) + " lines of code" + (main.isEmpty() ? "" : ", mostly " + String.join(", ", main)) + ".";
            String second = "It offers " + plural(cnt("services.total"), "service") + " and " + plural(cnt("screens.total"), "screen")
                    + ", runs " + plural(cnt("batch_jobs.total"), "batch", "batches") + ", uses " + plural(tables().size(), "table")
                    + " through " + plural(cnt("sql.total"), "SQL statement") + " and talks to "
                    + plural(cnt("integrations.total"), "other system") + ".";
            return List.of(List.of(first, second), "the scan (run the Copilot analysis for a business description)");
        }

        Map<String, Counter> componentCounts() {
            Map<String, Counter> per = new LinkedHashMap<>();
            String[][] kinds = {{"services", "services"}, {"screens", "screens"}, {"sql_statements", "sql"}, {"batch_jobs", "batch jobs"}};
            for (String[] k : kinds) {
                for (Map<String, Object> item : rd.items(k[0])) {
                    if (D.t(item, "component") && !(k[0].equals("screens") && D.t(item, "partial"))) {
                        per.computeIfAbsent(D.str(item, "component"), x -> new Counter()).add(k[1]);
                    }
                }
            }
            for (Map<String, Object> item : rd.items("integrations")) {
                for (String comp : D.strings(item, "components")) {
                    per.computeIfAbsent(comp, x -> new Counter()).add("integrations");
                }
            }
            return per;
        }

        Map<String, List<String>> languages() {
            Map<String, Counter> loc = new LinkedHashMap<>();
            for (Map<String, Object> f : rd.items("inventory")) {
                Object comp = D.get(f, "component");
                String lang = D.s(f, "language");
                if (Py.truthy(comp) && "source".equals(D.get(f, "origin", "source")) && D.t(f, "loc_code")) {
                    loc.computeIfAbsent(Py.str(comp), x -> new Counter()).add(lang, D.i(f, "loc_code"));
                }
            }
            Map<String, List<String>> out = new LinkedHashMap<>();
            loc.forEach((comp, cnt) -> {
                List<String> l = new ArrayList<>();
                cnt.mostCommon(4).forEach(en -> l.add(en.getKey()));
                out.put(comp, l);
            });
            return out;
        }

        List<String> moduleDescription(Map<String, Object> comp, Counter counts) {
            Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
            for (Map<String, Object> d : D.maps(D.map(e, "component_docs"), "components")) {
                docs.put(D.str(d, "component"), d);
            }
            Map<String, Object> doc = docs.get(D.str(comp, "name"));
            if (doc != null && D.t(doc, "purpose")) {
                return List.of(D.str(doc, "purpose"), "AI");
            }
            String kind = D.t(comp, "type") ? D.str(comp, "type") : "unknown";
            List<String> roles = new ArrayList<>();
            for (String r : D.strings(comp, "roles")) {
                if (!r.equals(kind)) {
                    roles.add(r);
                }
            }
            List<String> parts = new ArrayList<>();
            parts.add(capitalize(kind) + " module" + (roles.isEmpty() ? "" : ", also " + String.join(", ", roles)));
            List<String> made = new ArrayList<>();
            D.map(comp, "composition").forEach((k, v) -> made.add(k + " " + Py.str(v)));
            List<String> keys = new ArrayList<>(counts.keySet());
            keys.sort(SummaryReport::cmp);
            List<String> what = new ArrayList<>();
            for (String k : keys) {
                what.add(plural(counts.get(k), k.endsWith("s") ? k.substring(0, k.length() - 1) : k, k));
            }
            if (!what.isEmpty()) {
                parts.add("with " + String.join(", ", what));
            }
            String text = String.join(" ", parts) + ".";
            if (!made.isEmpty()) {
                text += " Files: " + String.join(", ", made) + ".";
            }
            if (D.t(comp, "parts")) { // grouped from several modules or folders (decision 176)
                List<String> ps = new ArrayList<>();
                for (Object p : D.list(comp, "parts")) {
                    ps.add(Py.truthy(p) ? Py.str(p) : "(root)");
                }
                text += " Made of " + String.join(", ", ps) + ".";
            }
            return List.of(text, "scan");
        }

        @SuppressWarnings("unchecked")
        Section project() {
            List<Object> desc = description();
            List<String> intro = new ArrayList<>((List<String>) desc.get(0));
            String source = (String) desc.get(1);
            intro.add("Migration target: " + Py.str(D.get(rd.target, "label", "")) + ".");
            Map<String, Object> t = D.map(s, "totals");
            List<Map<String, Object>> comps = new ArrayList<>(rd.items("components"));
            comps.sort((a, b) -> {
                int x = Double.compare(D.num(D.or(b.get("loc_code"), 0L)), D.num(D.or(a.get("loc_code"), 0L)));
                return x != 0 ? x : cmp(D.str(a, "name"), D.str(b, "name"));
            });
            Map<String, Map<String, Object>> metrics = new LinkedHashMap<>();
            rd.items("metrics").forEach(m -> metrics.put(D.str(m, "component"), m));
            Map<String, Counter> counts = componentCounts();
            Map<String, List<String>> langs = languages();
            List<List<Object>> rows = new ArrayList<>();
            boolean grouped = comps.stream().anyMatch(x -> D.t(x, "build_group")); // Maven / Gradle multi-module
            for (Map<String, Object> comp : comps) {
                String name = D.str(comp, "name");
                Map<String, Object> m = metrics.getOrDefault(name, Map.of());
                List<String> md = moduleDescription(comp, counts.getOrDefault(name, new Counter()));
                List<String> techs = new ArrayList<>(D.strings(comp, "technology"));
                techs.addAll(langs.getOrDefault(name, List.of()));
                techs = D.unique(techs);
                Object avg = m.get("complexity_avg");
                List<Object> r = row(name, Py.str(D.or(D.get(comp, "type"), "")), md.get(0) + (md.get(1).equals("AI") ? " (AI)" : ""),
                        String.join(", ", techs.subList(0, Math.min(5, techs.size()))), D.or(m.get("complexity"), ""),
                        avg instanceof Number || avg instanceof Boolean ? Fmt.fixed(D.num(avg), 1, false) : "",
                        D.or(D.get(comp, "files"), 0L), D.or(D.get(comp, "loc_code"), 0L));
                if (grouped) {
                    r.add(String.join(" > ", D.strings(comp, "build_group")));
                }
                rows.add(r);
            }
            Map<String, Map<String, Object>> changes = Servers.aiChanges(e);
            List<List<Object>> stack = new ArrayList<>();
            for (Map<String, Object> x : D.maps(s, "tech_stack")) {
                List<Object> r = row(D.get(x, "name", ""), D.s(x, "version"), D.s(x, "category"), eol(x));
                if (!changes.isEmpty()) {
                    Map<String, Object> ch = changes.get(Py.str(D.get(x, "name", "")).toLowerCase(Locale.ROOT));
                    r.add(ch != null ? Py.str(ch.get("target")) + " (AI)" : "");
                }
                stack.add(r);
            }
            Counter langLoc = new Counter();
            D.map(s, "by_language").forEach((k, v) -> {
                if (D.t(v, "loc_code")) {
                    langLoc.add(k, D.i(v, "loc_code"));
                }
            });
            List<List<Object>> ll = top(langLoc, 8);
            List<Map<String, Object>> servers = Servers.serverRows(rd, e);
            boolean targeted = !rd.auditOnly();
            List<List<Object>> serverTable = new ArrayList<>();
            for (Map<String, Object> r : servers) {
                long more = D.i(r, "more_files");
                List<Object> cells = row(r.get("name"), r.get("kind"), r.get("version"), r.get("eol"),
                        String.join(", ", D.strings(r, "components")),
                        String.join(", ", D.strings(r, "files")) + (more > 0 ? " (+" + more + " more)" : ""));
                if (targeted) {
                    cells.add(r.get("target"));
                }
                serverTable.add(cells);
            }
            long nServers = servers.stream().filter(r -> !"runtime".equals(r.get("category"))).count();
            if (nServers > 0) {
                List<String> names = new ArrayList<>();
                for (Map<String, Object> r : servers) {
                    if (!"runtime".equals(r.get("category"))) {
                        names.add(D.str(r, "name") + (D.t(r, "version") ? " " + D.str(r, "version") : ""));
                    }
                }
                intro.add("Runs on " + String.join(", ", names) + ".");
            }
            Counter bands = new Counter();
            rows.forEach(r -> {
                if (Py.truthy(r.get(4))) {
                    bands.add(Py.str(r.get(4)));
                }
            });
            List<String> cl = new ArrayList<>();
            for (String b : COMPLEXITY_ORDER) {
                if (bands.count(b) > 0) {
                    cl.add(b);
                }
            }
            List<List<Object>> locRows = rows.subList(0, Math.min(10, rows.size()));
            List<Object> locLabels = new ArrayList<>();
            List<Object> locValues = new ArrayList<>();
            locRows.forEach(r -> {
                locLabels.add(r.get(0));
                locValues.add(r.get(7));
            });
            List<Object> bandValues = new ArrayList<>();
            List<String> bandColors = new ArrayList<>();
            Map<String, String> bandToken = Map.of("Simple", "good", "Medium", "warning", "Complex", "serious", "Very complex", "critical");
            cl.forEach(b -> {
                bandValues.add(bands.count(b));
                bandColors.add(bandToken.get(b));
            });
            List<String> stackCols = new ArrayList<>(List.of("Technology", "Version", "Category", "End of life"));
            if (!changes.isEmpty()) {
                stackCols.add("Target (AI)");
            }
            List<String> serverCols = new ArrayList<>(List.of("Product", "Kind", "Version", "End of life", "Modules", "Found in"));
            if (targeted) {
                serverCols.add("Target");
            }
            List<String> moduleCols = new ArrayList<>(List.of("Module", "Type", "Description", "Technology", "Complexity",
                    "Avg. cyclomatic", "Files", "Lines of code"));
            if (grouped) {
                moduleCols.add("Maven group");
            }
            return new Section("project", "Project", "Everyone", "series-1", intro,
                    List.of(kpi("Lines of code", D.get(t, "loc_code", 0L)), kpi("Files", D.get(t, "files", 0L)),
                            kpi("Modules", D.get(t, "components", 0L)), kpi("Services", Fmt.at(c, "services.total")),
                            kpi("Screens", Fmt.at(c, "screens.total")), kpi("Technologies", (long) stack.size()),
                            kpi("App and web servers", nServers)),
                    List.of(new Chart("loc-language", "Lines of code by language", "doughnut", ll.get(0), ll.get(1),
                                    SERIES.subList(0, Math.min(ll.get(0).size(), SERIES.size())), "lines"),
                            new Chart("loc-module", "Lines of code by module", "hbar", locLabels, locValues, List.of("series-1"), "lines"),
                            new Chart("complexity", "Modules by complexity", "bar", cl, bandValues, bandColors, "modules")),
                    List.of(new Table("tech-stack", "Technology stack", stackCols, stack,
                                    !changes.isEmpty() ? "Target (AI): what the Copilot analysis says replaces it." : ""),
                            new Table("servers", "Application and web servers", serverCols, serverTable,
                                    "Found in deployment descriptors, server configuration, Dockerfiles, start "
                                            + "scripts and vendor libraries."
                                            + (targeted ? " The target is the default from rules/target-mapping.yaml for "
                                                    + Py.str(rd.target.get("label")) + ", or the AI's target architecture when "
                                                    + "marked (AI)." : ""),
                                    "No application or web server found in the code."),
                            new Table("modules", "Modules", moduleCols, rows,
                                    "Complexity is the band of the module's services and screens (weights in "
                                            + "audit.yaml); descriptions marked (AI) come from the Copilot analysis, the "
                                            + "others from the scan."
                                            + (grouped ? " Maven group: the parent / aggregator poms the module sits in (they are "
                                                    + "groups, not modules)." : ""))),
                    "Description from " + source + ".", "");
        }

        // ---- 2 database -----------------------------------------------------------------------

        List<Map<String, Object>> tables() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> o : rd.items("db_objects")) {
                if ("table".equals(D.get(o, "object_type"))) {
                    out.add(o);
                }
            }
            return out;
        }

        Section data() {
            Map<String, Object> sql = D.map(c, "sql");
            Map<String, Object> grades = D.map(c, "pg_grades");
            long total = Fmt.n(sql, "total");
            List<Map<String, Object>> tables = tables();
            tables.sort((a, b) -> {
                int x = Integer.compare(D.list(b, "referenced_by").size(), D.list(a, "referenced_by").size());
                return x != 0 ? x : cmp(D.str(a, "name"), D.str(b, "name"));
            });
            Map<String, Object> objects = D.map(c, "db_objects");
            long procs = Fmt.n(c, "stored_procedures");
            List<Map<String, Object>> dbs = new ArrayList<>();
            rd.items("integrations").forEach(i -> {
                if ("DB".equals(D.get(i, "type"))) {
                    dbs.add(i);
                }
            });
            List<String> dbTech = new ArrayList<>();
            for (Map<String, Object> x : D.maps(s, "tech_stack")) {
                if ("database".equals(D.get(x, "category")) && !D.str(x, "name").contains(" driver")) {
                    dbTech.add(D.str(x, "name")); // the products
                }
            }
            Map<String, Counter> byComp = new LinkedHashMap<>();
            Counter features = new Counter();
            for (Map<String, Object> st : rd.items("sql_statements")) {
                Counter bc = byComp.computeIfAbsent(D.t(st, "component") ? D.str(st, "component") : "(no module)", k -> new Counter());
                bc.add("total");
                bc.add(D.t(st, "pg_grade") ? D.str(st, "pg_grade") : "ungraded");
                if (D.t(st, "dynamic")) {
                    bc.add("dynamic");
                }
                D.strings(st, "vendor_features").forEach(features::add);
            }
            List<String> intro = new ArrayList<>();
            intro.add("The code holds " + plural(total, "SQL statement") + " (" + num(D.get(sql, "distinct", 0L)) + " distinct, "
                    + num(D.get(sql, "dynamic", 0L)) + " built at run time) on " + plural(tables.size(), "table")
                    + (dbTech.isEmpty() ? "" : " in " + String.join(", ", D.unique(dbTech))) + ".");
            boolean graded = !rd.auditOnly(); // audit only: no target database, no grades
            if (graded) {
                intro.add(pct(Fmt.n(grades, "auto"), total) + " convert to PostgreSQL automatically, " + pct(Fmt.n(grades, "review"), total)
                        + " need a review and " + pct(Fmt.n(grades, "manual"), total) + " a manual rewrite"
                        + (procs > 0 ? "; " + plural(procs, "stored procedure") + " to migrate." : "."));
            } else if (procs > 0) {
                intro.add("The database holds " + plural(procs, "stored procedure") + ".");
            }
            // The Copilot analysis adds words only: business data names (functional stage) and a
            // migration approach per object (data_migration stage); every count stays the scan's.
            Map<String, Object> dm = D.map(e, "data_migration");
            Map<String, Map<String, Object>> plans = new LinkedHashMap<>();
            D.maps(dm, "objects").forEach(p -> plans.put(D.str(p, "db_object_id"), p));
            Map<String, List<String>> entities = new LinkedHashMap<>();
            for (Map<String, Object> ent : D.maps(fn, "data_entities")) {
                for (String oid : D.strings(ent, "db_object_ids")) {
                    entities.computeIfAbsent(oid, k -> new ArrayList<>()).add(D.str(ent, "name"));
                }
            }
            if (D.t(dm, "strategy")) {
                intro.add("Migration approach (AI): " + D.str(dm, "strategy"));
            }
            Counter types = new Counter();
            D.map(sql, "by_type").forEach((k, v) -> types.add(k, (long) D.num(v)));
            List<List<Object>> tt = top(types, 8);
            List<String> crudCols = new ArrayList<>(List.of("Table", "SQL statements", "CRUD"));
            List<List<Object>> crudRows = new ArrayList<>();
            for (Map<String, Object> o : tables) {
                StringBuilder sb = new StringBuilder();
                Map<String, Object> crud = D.map(o, "crud");
                for (char k : "CRUD".toCharArray()) {
                    if (crud.containsKey(String.valueOf(k))) {
                        sb.append(k);
                    }
                }
                crudRows.add(row(o.get("name"), (long) D.list(o, "referenced_by").size(), sb.toString()));
            }
            if (tables.stream().anyMatch(o -> entities.containsKey(D.str(o, "id")))) {
                crudCols.add("Business data (AI)");
                for (int i = 0; i < tables.size(); i++) {
                    crudRows.get(i).add(String.join(", ", entities.getOrDefault(D.str(tables.get(i), "id"), List.of())));
                }
            }
            if (tables.stream().anyMatch(o -> plans.containsKey(D.str(o, "id")))) {
                crudCols.add("Migration (AI)");
                for (int i = 0; i < tables.size(); i++) {
                    crudRows.get(i).add(planText(plans.get(D.str(tables.get(i), "id"))));
                }
            }
            List<List<Object>> planRows = new ArrayList<>();
            plans.forEach((oid, p) -> {
                Map<String, Object> obj = rd.byId.getOrDefault(oid, Map.of());
                planRows.add(row(D.t(obj, "name") ? D.str(obj, "name") : oid, D.s(obj, "object_type"), D.s(p, "approach"), D.s(p, "notes")));
            });
            planRows.sort((a, b) -> {
                int x = cmp((String) a.get(1), (String) b.get(1));
                return x != 0 ? x : cmp((String) a.get(0), (String) b.get(0));
            });
            List<Map.Entry<String, Counter>> compEntries = new ArrayList<>(byComp.entrySet());
            compEntries.sort((a, b) -> {
                int x = Long.compare(b.getValue().count("total"), a.getValue().count("total"));
                return x != 0 ? x : cmp(a.getKey(), b.getKey());
            });
            List<List<Object>> compRows = new ArrayList<>();
            for (Map.Entry<String, Counter> en : compEntries) {
                Counter v = en.getValue();
                List<Object> r = row(en.getKey(), v.count("total"));
                if (graded) {
                    r.add(v.count("auto"));
                    r.add(v.count("review"));
                    r.add(v.count("manual"));
                }
                r.add(v.count("dynamic"));
                compRows.add(r);
            }
            List<Values.Tuple> kpis = new ArrayList<>(List.of(kpi("SQL statements", total), kpi("Distinct", D.get(sql, "distinct", 0L)),
                    kpi("Built at run time", D.get(sql, "dynamic", 0L)), kpi("Tables", (long) tables.size()),
                    kpi("Stored procedures", procs)));
            if (graded) {
                kpis.add(kpi("PostgreSQL auto", pct(Fmt.n(grades, "auto"), total)));
            }
            List<Chart> charts = new ArrayList<>();
            if (graded) {
                List<Object> gv = new ArrayList<>();
                List<String> gc = new ArrayList<>();
                GRADES.forEach(g -> {
                    gv.add(D.get(grades, g, 0L));
                    gc.add(GRADE_TOKEN.get(g));
                });
                charts.add(new Chart("pg-grade", "PostgreSQL readiness", "doughnut", GRADES, gv, gc, "statements"));
            }
            charts.add(new Chart("sql-type", "SQL statements by type", "bar", tt.get(0), tt.get(1), List.of("series-3"), "statements"));
            List<Object> topL = new ArrayList<>();
            List<Object> topV = new ArrayList<>();
            crudRows.subList(0, Math.min(10, crudRows.size())).forEach(r -> {
                topL.add(r.get(0));
                topV.add(r.get(1));
            });
            charts.add(new Chart("top-tables", "Most used tables", "hbar", topL, topV, List.of("series-6"), "statements"));
            List<List<Object>> dbRows = new ArrayList<>();
            dbs.forEach(i -> dbRows.add(row(i.get("target"), D.get(i, "usages", 0L), String.join(", ", D.strings(i, "components")))));
            List<List<Object>> featureRows = new ArrayList<>();
            features.mostCommon(15).forEach(en -> featureRows.add(row(en.getKey(), en.getValue())));
            List<List<Object>> objectRows = new ArrayList<>();
            new TreeMap<String, Object>(SummaryReport::cmp) {
                {
                    putAll(objects);
                }
            }.forEach((k, v) -> objectRows.add(row(k, v)));
            List<String> compCols = new ArrayList<>(List.of("Module", "Statements"));
            if (graded) {
                compCols.addAll(List.of("Auto", "Review", "Manual"));
            }
            compCols.add("Built at run time");
            List<Table> tablesOut = new ArrayList<>(List.of(
                    new Table("tables", "Tables", crudCols, crudRows, "CRUD: Create, Read, Update, Delete seen in the code."
                            + (crudCols.size() > 3 ? " Columns marked (AI) come from the Copilot analysis." : "")),
                    new Table("sql-by-module", "SQL by module", compCols, compRows,
                            graded ? "Auto / Review / Manual: PostgreSQL grade of each statement." : ""),
                    new Table("databases", "Databases connected", List.of("Target", "Usages", "Modules"), dbRows, ""),
                    new Table("vendor-features", "Vendor-specific SQL features", List.of("Feature", "Statements"), featureRows, ""),
                    new Table("object-types", "Database objects", List.of("Kind", "Count"), objectRows, "")));
            if (!planRows.isEmpty()) {
                tablesOut.add(new Table("migration-plan", "Migration plan per object (AI)", List.of("Object", "Kind", "Approach", "Notes"),
                        planRows, "Written by the Copilot analysis (data migration stage): auto, convert, rewrite or retire."));
            }
            return new Section("data", "Database and queries", "Architects and DBAs", "series-3", intro, kpis, charts, tablesOut,
                    !plans.isEmpty() || !entities.isEmpty() || D.t(dm, "strategy")
                            ? "Text marked AI comes from the Copilot analysis; every number comes from the scan." : "", "");
        }

        // ---- 3 integrations -------------------------------------------------------------------

        Section integrations() {
            List<Map<String, Object>> items = new ArrayList<>(rd.items("integrations"));
            items.sort((a, b) -> {
                int x = cmp(Py.str(D.or(D.get(a, "purpose"), "")), Py.str(D.or(D.get(b, "purpose"), "")));
                if (x != 0) {
                    return x;
                }
                x = cmp(D.str(a, "type"), D.str(b, "type"));
                return x != 0 ? x : cmp(D.str(a, "target"), D.str(b, "target"));
            });
            Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
            D.maps(fn, "interfaces").forEach(x -> raw.put(D.str(x, "integration_id"), x));
            Map<String, Map<String, Object>> stories = rd.byRow(raw);
            Counter purposes = new Counter();
            Counter types = new Counter();
            List<Map<String, Object>> logins = new ArrayList<>();
            long web = 0;
            long filesN = 0;
            long msg = 0;
            long usages = 0;
            for (Map<String, Object> i : items) {
                purposes.add(D.t(i, "purpose") ? D.str(i, "purpose") : D.str(i, "type").toLowerCase(Locale.ROOT));
                types.add(D.str(i, "type"));
                Object purpose = D.get(i, "purpose");
                if ("login / authentication".equals(purpose)) {
                    logins.add(i);
                }
                if ("HTTP".equals(i.get("type")) || "SOAP".equals(i.get("type"))) {
                    web++;
                }
                if ("file transfer".equals(purpose)) {
                    filesN++;
                }
                if ("messaging".equals(purpose)) {
                    msg++;
                }
                usages += D.i(i, "usages");
            }
            Set<String> idps = new TreeSet<>(SummaryReport::cmp);
            logins.forEach(i -> idps.add(idp(i)));
            List<String> intro = new ArrayList<>();
            intro.add(app + " talks to " + plural(items.size(), "other system") + ": " + num(web) + " over HTTP/HTTPS, " + num(filesN)
                    + " by file transfer, " + num(msg) + " by messaging"
                    + (logins.isEmpty() ? "" : ", and signs users in through " + String.join(", ", idps)) + ".");
            intro.add("Each system is listed once, with every place in the code that uses it: the "
                    + "endpoints called on one host, the queues and channels of one queue manager and the "
                    + "files of one server are one row.");
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> i : items) {
                Map<String, Object> story = stories.get(D.str(i, "id"));
                String desc;
                if (story != null && D.t(story, "purpose")) {
                    String partner = D.t(story, "partner") ? D.str(story, "partner") + ": " : "";
                    desc = partner + D.str(story, "purpose") + " (AI)";
                } else {
                    desc = D.s(i, "description");
                }
                rows.add(row(D.s(i, "purpose"), i.get("type"), i.get("target"), String.join(", ", D.strings(i, "endpoints")),
                        D.s(i, "direction"), D.get(i, "usages", 0L), String.join(", ", D.strings(i, "components")), desc));
            }
            List<List<Object>> pt = top(purposes, 8);
            List<List<Object>> tt = top(types, 8);
            WebServices ws = webServices();
            if (!ws.rows.isEmpty()) {
                intro.add("It offers " + plural(ws.kpis.get("offered"), "web service") + " and calls " + num(ws.kpis.get("called")) + ": "
                        + num(ws.kpis.get("rest")) + " REST and " + num(ws.kpis.get("soap")) + " SOAP, with "
                        + plural(ws.kpis.get("operations"), "operation") + " in all.");
            }
            List<Chart> charts = new ArrayList<>(List.of(
                    new Chart("by-purpose", "Integrations by purpose", "doughnut", pt.get(0), pt.get(1),
                            SERIES.subList(0, Math.min(pt.get(0).size(), SERIES.size())), "integrations"),
                    new Chart("by-type", "Integrations by type", "bar", tt.get(0), tt.get(1), List.of("series-2"), "integrations")));
            if (!ws.rows.isEmpty()) {
                charts.add(ws.chart);
            }
            int webCols = ws.rows.isEmpty() ? WEB_COLUMNS.size() - 1 : ws.rows.get(0).size();
            return new Section("integrations", "Integrations", "Architects", "series-2", intro,
                    List.of(kpi("Integrations", (long) items.size()), kpi("HTTP / HTTPS", web), kpi("File transfer", filesN),
                            kpi("Messaging", msg), kpi("Login / authentication", (long) logins.size()), kpi("Usages in code", usages),
                            kpi("Web services offered", ws.kpis.get("offered")), kpi("Web services called", ws.kpis.get("called"))),
                    charts,
                    List.of(new Table("integrations", "Integrations", List.of("Purpose", "Type", "Target", "Endpoints / queues / files",
                                    "Direction", "Usages", "Modules", "Description"), rows,
                                    "Descriptions marked (AI) come from the Copilot analysis, the others from the scan."),
                            new Table("web-services", "Web services (REST and SOAP)", WEB_COLUMNS.subList(0, webCols), ws.rows,
                                    "Offered: other systems call the application; called: the application calls "
                                            + "them. Text marked (AI) comes from the Copilot analysis, the rest from the "
                                            + "scan. Every operation is in the technical report (Web services page).",
                                    "No REST or SOAP web service found.")),
                    "", "");
        }

        record WebServices(List<List<Object>> rows, Map<String, Long> kpis, Chart chart) {}

        WebServices webServices() {
            List<Map<String, Object>> web = rd.items("web_services");
            Map<String, Map<String, Object>> stories = new LinkedHashMap<>();
            D.maps(fn, "web_services").forEach(x -> stories.put(D.str(x, "web_service_id"), x));
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> w : web) {
                List<String> text = Catalogues.wsText(w, stories.get(D.str(w, "id")));
                rows.add(row(w.get("side"), w.get("style"), w.get("name"), D.s(w, "url"), (long) D.list(w, "operations").size(),
                        String.join(", ", D.strings(w, "frameworks")), D.s(w, "component"), text.get(0), text.get(1)));
            }
            if (rows.stream().noneMatch(r -> Py.truthy(r.get(8)))) { // the AI column only when the analysis has it
                rows.replaceAll(r -> new ArrayList<>(r.subList(0, 8)));
            }
            Map<String, Long> kpis = new LinkedHashMap<>();
            kpis.put("offered", web.stream().filter(w -> "offered".equals(w.get("side"))).count());
            kpis.put("called", web.stream().filter(w -> "called".equals(w.get("side"))).count());
            kpis.put("rest", web.stream().filter(w -> "REST".equals(w.get("style"))).count());
            kpis.put("soap", web.stream().filter(w -> "SOAP".equals(w.get("style"))).count());
            kpis.put("operations", web.stream().mapToLong(w -> D.list(w, "operations").size()).sum());
            List<Object> labels = new ArrayList<>();
            for (String side : List.of("offered", "called")) {
                for (String st : List.of("REST", "SOAP")) {
                    labels.add(st + " " + side);
                }
            }
            List<Object> values = new ArrayList<>();
            for (Object lab : labels) {
                values.add(web.stream().filter(w -> (D.str(w, "style") + " " + D.str(w, "side")).equals(lab)).count());
            }
            Chart chart = new Chart("web-services", "Web services offered and called", "bar", labels, values,
                    List.of("series-1", "series-3", "series-2", "series-4"), "web services");
            return new WebServices(rows, kpis, chart);
        }

        static String idp(Map<String, Object> i) {
            Matcher m = IDP.matcher(D.s(i, "description"));
            return m.lookingAt() ? m.group(1) : "an identity provider";
        }

        // ---- 4 batch --------------------------------------------------------------------------

        private static final Map<String, String> SCHEDULER_SOURCES = Map.of(
                "autosys", "scheduler", "cron", "scheduler", "jcl", "scheduler", "spring-batch", "scheduler", "node-cron", "scheduler");

        Section batch() {
            List<Flows.Flow> bflows = Flows.batchFlows(rd);
            Map<String, String> said = new LinkedHashMap<>();
            for (Flows.Flow f : bflows) {
                List<Map<String, Object>> com = Flows.codeComments(rd, f);
                if (!com.isEmpty()) {
                    said.put(f.id(), D.strings(com.get(0), "lines").get(0));
                }
            }
            Map<String, Map<String, Object>> stories = new LinkedHashMap<>();
            D.maps(fn, "batches").forEach(b -> stories.put(D.str(b, "job_id"), b));
            List<Map<String, Object>> rows = Batches.batchList(rd, bflows, said, stories);
            Map<String, Object> bj = D.map(c, "batch_jobs");
            Counter kinds = new Counter();
            Counter sources = new Counter();
            for (Map<String, Object> r : rows) {
                kinds.add(D.str(r, "kind"));
                sources.add(SCHEDULER_SOURCES.getOrDefault(D.str(r, "when_from"), D.str(r, "when_from")));
            }
            Map<String, String> sourceWords = Map.of("scheduler", "scheduler definition", "AI (Copilot)", "Copilot analysis",
                    "code", "stated in the code", "none", "no schedule found");
            List<String> sl = new ArrayList<>();
            for (String k : List.of("scheduler", "AI (Copilot)", "code", "none")) {
                if (sources.count(k) > 0) {
                    sl.add(k);
                }
            }
            List<String> gaps = new ArrayList<>();
            for (Map<String, Object> cv : D.maps(rd.run, "coverage")) {
                Object kind = D.get(cv, "kind");
                if (("autosys".equals(kind) || "crontab".equals(kind)) && "missing".equals(D.get(cv, "status"))) {
                    gaps.add(Py.str(D.get(cv, "label")));
                }
            }
            List<String> intro = new ArrayList<>();
            intro.add(plural(Fmt.n(bj, "total"), "batch", "batches") + " counted: " + num(D.get(bj, "scheduled", 0L)) + " scheduled jobs and "
                    + num(D.get(bj, "unscheduled", 0L)) + " batch modules that no scheduler definition starts"
                    + (D.t(bj, "boxes") ? " (" + plural(Fmt.n(bj, "boxes"), "box", "boxes") + " group the jobs)" : "") + ".");
            intro.add(!gaps.isEmpty() ? "Not provided: " + String.join(", ", gaps) + "; when a batch runs is then taken "
                    + "from what its code states, or shown as not found." : "Schedules come from the scheduler definitions provided.");
            List<List<Object>> tableRows = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                String from = SCHEDULER_SOURCES.getOrDefault(D.str(r, "when_from"), D.str(r, "when_from"));
                tableRows.add(row(r.get("name"), r.get("kind"), D.s(r, "component"),
                        D.str(r, "what") + ("AI (Copilot)".equals(r.get("what_from")) ? " (AI)" : ""),
                        D.str(r, "when") + ("AI (Copilot)".equals(r.get("when_from")) ? " (AI)" : ""),
                        sourceWords.getOrDefault(from, D.str(r, "when_from")), r.get("steps")));
            }
            List<Object> kl = new ArrayList<>();
            List<Object> kv = new ArrayList<>();
            kinds.mostCommon().forEach(en -> {
                kl.add(en.getKey());
                kv.add(en.getValue());
            });
            List<Object> srcLabels = new ArrayList<>();
            List<Object> srcValues = new ArrayList<>();
            List<String> srcColors = new ArrayList<>();
            Map<String, String> srcToken = Map.of("scheduler", "good", "AI (Copilot)", "series-7", "code", "warning", "none", "critical");
            sl.forEach(k -> {
                srcLabels.add(sourceWords.get(k));
                srcValues.add(sources.count(k));
                srcColors.add(srcToken.get(k));
            });
            boolean ai = rows.stream().anyMatch(r -> "AI (Copilot)".equals(r.get("what_from")) || "AI (Copilot)".equals(r.get("when_from")));
            return new Section("batch", "Batches", "Operations and architects", "series-7", intro,
                    List.of(kpi("Batches", D.get(bj, "total", 0L)), kpi("Scheduled jobs", D.get(bj, "scheduled", 0L)),
                            kpi("No scheduler definition", D.get(bj, "unscheduled", 0L)), kpi("Boxes", D.get(bj, "boxes", 0L)),
                            kpi("In the list", (long) rows.size())),
                    List.of(new Chart("batch-kind", "Batches by kind", "bar", kl, kv, List.of("series-7"), "batches"),
                            new Chart("schedule-source", "Where the schedule comes from", "doughnut", srcLabels, srcValues, srcColors,
                                    "batches")),
                    List.of(new Table("batches", "All batches", List.of("Batch", "Kind", "Module", "What it does", "When it runs",
                            "Schedule from", "Steps"), tableRows,
                            "What it does: Copilot analysis (marked AI), else the scheduler's "
                                    + "description, else the code's header comment, else the scan.")),
                    ai ? "Text marked AI comes from the Copilot analysis; every number comes from the scan." : "", "");
        }

        // ---- 5 dependency vulnerabilities -----------------------------------------------------

        static String foundBy(Collection<?> sources) {
            Set<String> set = new TreeSet<>(SummaryReport::cmp);
            if (sources != null) {
                sources.forEach(x -> set.add(Py.str(x)));
            }
            List<String> names = new ArrayList<>();
            set.forEach(x -> names.add(SCANNERS.getOrDefault(x, x)));
            return names.size() > 1 ? "both" : names.isEmpty() ? "" : names.get(0);
        }

        /** Library id to the modules whose build files or folders declare it. */
        Map<String, List<String>> libraryModules() {
            Map<String, Object> compOf = new LinkedHashMap<>();
            rd.items("inventory").forEach(f -> compOf.put(D.str(f, "path"), D.get(f, "component")));
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Map<String, Object> lib : rd.items("libraries")) {
                Set<String> comps = new TreeSet<>(SummaryReport::cmp);
                for (String p : D.strings(lib, "where_used")) {
                    Object comp = compOf.get(p);
                    if (Py.truthy(comp)) {
                        comps.add(Py.str(comp));
                    }
                }
                out.put(D.str(lib, "id"), new ArrayList<>(comps));
            }
            return out;
        }

        private static final class Vuln {
            String severity = "unknown";
            final Set<String> sources = new LinkedHashSet<>();
            boolean fix;
        }

        private static Object cvssOr0(Object v) {
            return v == null || "".equals(v) ? 0L : v;
        }

        Section security() {
            List<Map<String, Object>> libs = rd.items("libraries");
            Map<String, Object> vl = D.map(c, "vulnerable_libraries");
            List<Map<String, Object>> sources = D.maps(rd.run, "vulnerability_sources");
            List<Map<String, Object>> checked = new ArrayList<>();
            sources.forEach(sr -> {
                if ("imported".equals(D.get(sr, "status")) || "ran".equals(D.get(sr, "status"))) {
                    checked.add(sr);
                }
            });
            Map<String, List<String>> modules = libraryModules();
            // The AI upgrade plan (stage dependency_plan): its theme and action per library.
            Map<String, String> plan = new LinkedHashMap<>();
            for (Map<String, Object> th : D.maps(D.map(e, "dependency_plan"), "themes")) {
                String words = Py.str(D.get(th, "name", "")) + ": " + Py.str(D.get(th, "action", ""));
                words = com.legacyaudit.engine.analyzers.TextUtil.strip(words, ": ");
                if (D.t(th, "wave")) {
                    words += " (wave " + D.str(th, "wave") + ")";
                }
                for (String lid : D.strings(th, "library_ids")) {
                    plan.putIfAbsent(lid, words);
                }
            }
            // One row per vulnerability and library; per id, the worst severity and every scanner.
            List<List<Object>> rows = new ArrayList<>();
            Map<String, Vuln> byId = new LinkedHashMap<>();
            List<List<Object>> libRows = new ArrayList<>();
            Counter perLib = new Counter();
            Map<String, Set<String>> perModule = new LinkedHashMap<>();
            for (Map<String, Object> lib : libs) {
                List<Map<String, Object>> vulns = D.maps(lib, "vulnerabilities");
                if (vulns.isEmpty()) {
                    continue;
                }
                String name = D.t(lib, "group") ? D.str(lib, "group") + ":" + D.str(lib, "artifact") : D.str(lib, "artifact");
                for (Map<String, Object> v : vulns) {
                    Vuln cur = byId.computeIfAbsent(D.str(v, "id"), k -> new Vuln());
                    if (SEVERITIES.indexOf(D.str(v, "severity")) < SEVERITIES.indexOf(cur.severity)) {
                        cur.severity = D.str(v, "severity");
                    }
                    cur.sources.addAll(D.strings(v, "sources"));
                    cur.fix = cur.fix || D.t(v, "fixed_versions");
                    String fixed = String.join(", ", D.strings(v, "fixed_versions"));
                    rows.add(row(v.get("id"), v.get("severity"), D.get(v, "cvss") != null ? v.get("cvss") : "", name, D.s(lib, "version"),
                            fixed.isEmpty() ? "not known" : fixed, foundBy(D.list(v, "sources")), String.join(", ", D.strings(v, "aliases")),
                            D.s(v, "description")));
                    for (String m : modules.getOrDefault(D.str(lib, "id"), List.of())) {
                        perModule.computeIfAbsent(m, k -> new HashSet<>()).add(D.str(v, "id"));
                    }
                }
                perLib.put(com.legacyaudit.engine.analyzers.TextUtil.strip(D.str(lib, "artifact") + " " + D.s(lib, "version")),
                        (long) vulns.size());
                Map<String, Object> rec = D.map(lib, "recommendation");
                String fix = D.s(rec, "action");
                if (D.t(rec, "target")) {
                    fix = fix.isEmpty() ? D.str(rec, "target") : fix + ": " + D.str(rec, "target");
                }
                Object maxCvss = 0L;
                for (Map<String, Object> v : vulns) {
                    Object x = D.or(D.get(v, "cvss"), 0L);
                    if (Values.compare(x, maxCvss) > 0) {
                        maxCvss = x;
                    }
                }
                Set<Object> allSources = new LinkedHashSet<>();
                vulns.forEach(v -> allSources.addAll(D.list(v, "sources")));
                List<Object> r = row(name, D.s(lib, "version"), (long) vulns.size(), D.s(lib, "max_severity"),
                        Py.truthy(maxCvss) ? maxCvss : "", foundBy(allSources), fix,
                        D.t(lib, "eol") ? "yes" : Boolean.FALSE.equals(D.get(lib, "eol")) ? "no" : "",
                        String.join(", ", modules.getOrDefault(D.str(lib, "id"), List.of())));
                if (!plan.isEmpty()) {
                    r.add(plan.containsKey(D.str(lib, "id")) ? plan.get(D.str(lib, "id")) + " (AI)" : "");
                }
                libRows.add(r);
            }
            rows.sort((a, b) -> {
                int x = Integer.compare(SEVERITIES.indexOf(Py.str(a.get(1))), SEVERITIES.indexOf(Py.str(b.get(1))));
                if (x != 0) {
                    return x;
                }
                x = Values.compare(cvssOr0(b.get(2)), cvssOr0(a.get(2)));
                if (x != 0) {
                    return x;
                }
                x = cmp(Py.str(a.get(0)), Py.str(b.get(0)));
                return x != 0 ? x : cmp(Py.str(a.get(3)), Py.str(b.get(3)));
            });
            libRows.sort((a, b) -> {
                String sa = Py.truthy(a.get(3)) ? Py.str(a.get(3)) : "unknown";
                String sb = Py.truthy(b.get(3)) ? Py.str(b.get(3)) : "unknown";
                int x = Integer.compare(SEVERITIES.indexOf(sa), SEVERITIES.indexOf(sb));
                if (x != 0) {
                    return x;
                }
                x = Long.compare((Long) b.get(2), (Long) a.get(2));
                return x != 0 ? x : cmp(Py.str(a.get(0)), Py.str(b.get(0)));
            });
            long total = vl.containsKey("vulnerabilities") ? (long) D.num(vl.get("vulnerabilities")) : byId.size();
            Counter sev = new Counter();
            Counter found = new Counter();
            long fixable = 0;
            for (Vuln v : byId.values()) {
                sev.add(v.severity);
                found.add(foundBy(v.sources));
                if (v.fix) {
                    fixable++;
                }
            }
            List<List<Object>> sourceRows = new ArrayList<>();
            for (Map<String, Object> src : sources) {
                String tool = Py.str(D.get(src, "name", ""));
                String status = Py.str(D.or(D.get(src, "status"), ""));
                String result = SCANNER_STATUS.getOrDefault(status, status);
                if (D.t(src, "detail")) {
                    result += " (" + D.str(src, "detail") + ")";
                }
                long ids = 0;
                long alone = 0;
                for (Vuln v : byId.values()) {
                    if (v.sources.contains(tool)) {
                        ids++;
                        if (v.sources.size() == 1) {
                            alone++;
                        }
                    }
                }
                boolean ok = status.equals("imported") || status.equals("ran");
                sourceRows.add(row(SCANNERS.getOrDefault(tool, tool), result, D.s(src, "database_date"),
                        String.join(", ", D.strings(src, "reports")), ok ? (Object) ids : "", ok ? (Object) alone : ""));
            }
            List<String> checkedNames = new ArrayList<>();
            checked.forEach(sr -> checkedNames.add(SCANNERS.getOrDefault(D.str(sr, "name"), D.str(sr, "name"))));
            String names = String.join(" and ", checkedNames);
            List<String> unused = new ArrayList<>();
            for (Map<String, Object> sr : sources) {
                if (!checked.contains(sr)) {
                    Object why = D.t(sr, "detail") ? D.get(sr, "detail")
                            : SCANNER_STATUS.containsKey(Py.str(D.get(sr, "status", ""))) ? SCANNER_STATUS.get(Py.str(D.get(sr, "status", "")))
                            : D.get(sr, "status");
                    unused.add(SCANNERS.getOrDefault(D.str(sr, "name"), D.str(sr, "name")) + " (" + Py.str(why) + ")");
                }
            }
            String how = "To add the check, put the JSON report of OWASP Dependency-Check or OSV-Scanner "
                    + "(or both) in audit-inputs/security/, or install the tools so the scan runs them, then scan again.";
            List<String> intro = new ArrayList<>();
            if (libs.isEmpty()) {
                intro.add("No third-party libraries were found in the code, so there was nothing to check for known vulnerabilities.");
            } else if (checked.isEmpty()) {
                intro.add("No vulnerability check was done: neither OWASP Dependency-Check nor OSV-Scanner "
                        + "results were found for this run, so the " + plural(libs.size(), "library", "libraries")
                        + " found have not been checked. This does not mean they are safe.");
                intro.add(sources.stream().anyMatch(sr -> "failed".equals(D.get(sr, "status")))
                        ? "The scanners were found but did not finish; the reason for each is below "
                                + "and in the Vulnerability checks table. Fix it, then scan again."
                        : how);
            } else if (total == 0) {
                intro.add("No known vulnerabilities in the " + plural(libs.size(), "library", "libraries") + " checked with " + names + ".");
            } else {
                List<String> worst = new ArrayList<>();
                for (String k : SEVERITIES) {
                    if (sev.count(k) > 0) {
                        worst.add(num(sev.count(k)) + " " + k);
                    }
                }
                intro.add(plural(total, "known vulnerability", "known vulnerabilities") + " in "
                        + num(vl.containsKey("total") ? vl.get("total") : (long) libRows.size()) + " of "
                        + plural(libs.size(), "library", "libraries") + ": " + String.join(", ", worst) + ".");
                String byWhom;
                if (checked.size() > 1) {
                    List<String> only = new ArrayList<>();
                    for (String n : SCANNERS.values()) {
                        only.add(n + " only: " + num(found.count(n)));
                    }
                    byWhom = " (both: " + num(found.count("both")) + ", " + String.join(", ", only) + ").";
                } else {
                    byWhom = ".";
                }
                intro.add("Checked with " + names + "; each vulnerability is listed once, with the scanner(s) that found it" + byWhom);
                intro.add("A fixed version is known for " + num(fixable) + " of them; the libraries table "
                        + "gives the suggested upgrade or replacement.");
                List<List<Object>> top = rows.subList(0, Math.min(3, rows.size()));
                if (!top.isEmpty()) {
                    List<String> parts = new ArrayList<>();
                    for (List<Object> r : top) {
                        parts.add(Py.str(r.get(0)) + " in " + Py.str(r.get(3)) + " " + Py.str(r.get(4)) + " (" + Py.str(r.get(1))
                                + (!"".equals(r.get(2)) ? ", CVSS " + num(r.get(2)) : "") + ")");
                    }
                    intro.add("Fix first: " + String.join("; ", parts) + ".");
                }
            }
            Set<String> vulnerable = new HashSet<>();
            libs.forEach(lib -> {
                if (D.t(lib, "vulnerabilities")) {
                    vulnerable.add(D.str(lib, "id"));
                }
            });
            List<String> themes = new ArrayList<>();
            for (Map<String, Object> th : D.maps(D.map(e, "dependency_plan"), "themes")) {
                if (D.strings(th, "library_ids").stream().anyMatch(vulnerable::contains)) {
                    themes.add(Py.str(D.or(D.get(th, "name"), "")));
                }
            }
            if (!themes.isEmpty()) {
                themes.removeIf(String::isEmpty);
                intro.add("Upgrade plan (AI): " + String.join("; ", themes) + ".");
            }
            if (!libs.isEmpty() && !unused.isEmpty()) { // a scanner that was not used is a gap, said in so many words
                intro.add("Not used: " + String.join(", ", unused) + "."
                        + (!checked.isEmpty() ? " The two scanners use different databases and each finds issues the other "
                                + "misses: add the missing one's JSON report to audit-inputs/security/, or "
                                + "install it, and scan again." : ""));
            }
            List<Chart> charts = new ArrayList<>();
            if (total > 0) {
                List<Object> sl = new ArrayList<>();
                List<Object> sv = new ArrayList<>();
                List<String> sc = new ArrayList<>();
                for (String k : SEVERITIES) {
                    if (sev.count(k) > 0) {
                        sl.add(k);
                        sv.add(sev.count(k));
                        sc.add(SEVERITY_TOKEN.get(k));
                    }
                }
                List<Object> fl = new ArrayList<>();
                List<Object> fv = new ArrayList<>();
                List<String> fc = new ArrayList<>();
                Map<String, String> foundToken = Map.of("both", "good", "Dependency-Check", "series-1", "OSV-Scanner", "series-4");
                List<String> foundKeys = new ArrayList<>(List.of("both"));
                foundKeys.addAll(SCANNERS.values());
                for (String k : foundKeys) {
                    if (found.count(k) > 0) {
                        fl.add(k);
                        fv.add(found.count(k));
                        fc.add(foundToken.get(k));
                    }
                }
                List<Object> ll = new ArrayList<>();
                List<Object> lv = new ArrayList<>();
                perLib.mostCommon(10).forEach(en -> {
                    ll.add(en.getKey());
                    lv.add(en.getValue());
                });
                List<Map.Entry<String, Set<String>>> mods = new ArrayList<>(perModule.entrySet());
                mods.sort((a, b) -> {
                    int x = Integer.compare(b.getValue().size(), a.getValue().size());
                    return x != 0 ? x : cmp(a.getKey(), b.getKey());
                });
                mods = mods.subList(0, Math.min(10, mods.size()));
                List<Object> ml = new ArrayList<>();
                List<Object> mv = new ArrayList<>();
                mods.forEach(en -> {
                    ml.add(en.getKey());
                    mv.add((long) en.getValue().size());
                });
                charts = List.of(new Chart("by-severity", "Vulnerabilities by severity", "doughnut", sl, sv, sc, "vulnerabilities"),
                        new Chart("by-scanner", "Which scanner found them", "doughnut", fl, fv, fc, "vulnerabilities"),
                        new Chart("top-libraries", "Most vulnerable libraries", "hbar", ll, lv, List.of("series-8"), "vulnerabilities"),
                        new Chart("by-module", "Vulnerabilities by module", "bar", ml, mv, List.of("series-8"), "vulnerabilities"));
            }
            List<Values.Tuple> kpis = new ArrayList<>();
            if (!checked.isEmpty()) {
                kpis.add(kpi("Libraries", (long) libs.size()));
                kpis.add(kpi("Vulnerable libraries", vl.containsKey("total") ? vl.get("total") : (long) libRows.size()));
                kpis.add(kpi("Vulnerabilities", total));
                kpis.add(kpi("Critical", sev.count("critical")));
                kpis.add(kpi("High", sev.count("high")));
                kpis.add(kpi("Fix known", fixable));
                if (checked.size() > 1) {
                    kpis.add(kpi("Found by both scanners", found.count("both")));
                }
            } else {
                kpis.add(kpi("Libraries", (long) libs.size()));
                kpis.add(kpi("Vulnerability check", libs.isEmpty() ? "not needed" : "not done"));
            }
            String notChecked = "Not checked: no Dependency-Check or OSV-Scanner results for this run.";
            String empty = !checked.isEmpty() ? "None found." : notChecked;
            List<String> libCols = new ArrayList<>(List.of("Library", "Version", "Vulnerabilities", "Worst severity", "Highest CVSS",
                    "Found by", "Suggested fix", "End of life", "Modules"));
            if (!plan.isEmpty()) {
                libCols.add("Upgrade plan (AI)");
            }
            return new Section("security", "Dependency vulnerabilities", "Security and architects", "series-8", intro, kpis, charts,
                    List.of(new Table("vulnerabilities", "Top vulnerabilities", List.of("Vulnerability", "Severity", "CVSS", "Library",
                                    "Version", "Fixed in", "Found by", "Also known as", "What it is"), rows,
                                    "Worst first. One row per vulnerability and library; when both scanners "
                                            + "report the same issue (same CVE, GHSA or OSV id) it is one row with Found by = both.",
                                    empty),
                            new Table("vulnerable-libraries", "Vulnerable libraries", libCols, libRows,
                                    "Suggested fix: the upgrade or replacement from the dependency rules "
                                            + "(rules/dependency-upgrades.yaml)."
                                            + (!plan.isEmpty() ? " Upgrade plan (AI): the theme the Copilot analysis puts the library in "
                                                    + "and what it says to do." : ""),
                                    empty),
                            new Table("scanners", "Vulnerability checks", List.of("Scanner", "Result", "Database date", "Reports",
                                    "Vulnerabilities found", "Found only by this scanner"), sourceRows,
                                    "Database date: how fresh the scanner's vulnerability data was. A scanner "
                                            + "that was not used is a gap in the check, not a clean result.")),
                    !checked.isEmpty() ? "Every number comes from the scanners' reports matched to the libraries the scan found." : "",
                    !libs.isEmpty() && checked.isEmpty() ? intro.get(0) : "");
        }
    }

    /** The sections of a run's summary, without the hidden ones (the hosted site's admin switches). */
    public static List<Section> sections(RunData rd, Set<String> hide) {
        Builder b = new Builder(rd, Status.loadEnrichment(rd.runDir));
        List<Section> out = new ArrayList<>();
        Map<String, java.util.function.Supplier<Section>> builders = new LinkedHashMap<>();
        builders.put("project", b::project);
        builders.put("data", b::data);
        builders.put("integrations", b::integrations);
        builders.put("batch", b::batch);
        builders.put("security", b::security);
        builders.forEach((k, f) -> {
            if (!hide.contains(k)) {
                out.add(f.get());
            }
        });
        return out;
    }

    static String csvCell(Object v) {
        return v == null ? "" : Py.str(v);
    }

    static void writeCsv(Path path, Table t) throws IOException {
        StringBuilder sb = new StringBuilder("﻿");
        FactWriter.csvRow(sb, t.columns());
        for (List<Object> r : t.rows()) {
            List<String> cells = new ArrayList<>();
            r.forEach(v -> cells.add(csvCell(v)));
            FactWriter.csvRow(sb, cells);
        }
        Files.writeString(path, sb, StandardCharsets.UTF_8);
    }

    /** Write {@code reports/summary/} (index.html, summary.json, CSVs) for a run; returns the page. */
    public static Path build(Path runDir, Set<String> hideSections) {
        RunData rd = RunData.load(runDir);
        Path out = runDir.resolve("reports").resolve("summary");
        buildInto(rd, out, hideSections);
        Path latest = Status.latestCopy(runDir);
        if (latest != null) {
            Path dest = latest.resolve("reports").resolve("summary");
            Fmt.rmtree(dest);
            copyTree(out, dest);
        }
        return out.resolve("index.html");
    }

    static void copyTree(Path src, Path dest) {
        try (var walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dest.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void buildInto(RunData rd, Path out, Set<String> hideSections) {
        List<Section> sections = sections(rd, hideSections);
        try {
            Fmt.rmtree(out);
            Files.createDirectories(out.resolve("data"));
            Fmt.copyResources("com/legacyaudit/reports/assets/vendor", List.of("LICENSE-chartjs.md", "chart-4.4.7.umd.js"),
                    out.resolve("assets"));
            for (Section sec : sections) {
                for (Table t : sec.tables()) {
                    writeCsv(out.resolve("data").resolve(t.key() + ".csv"), t);
                }
            }
            String app = D.t(rd.run, "application") ? D.str(rd.run, "application") : "Application";
            List<Object> secMaps = new ArrayList<>();
            sections.forEach(sec -> secMaps.add(sec.asMap()));
            Files.writeString(out.resolve("summary.json"), PyJson.pretty(D.of("application", app, "run_id", rd.run.get("run_id"),
                    "sections", secMaps)), StandardCharsets.UTF_8);
            List<Object> charts = new ArrayList<>();
            for (Section sec : sections) {
                for (Chart ch : sec.charts()) {
                    List<String> cols = ch.colors().size() == ch.labels().size() ? ch.colors()
                            : java.util.Collections.nCopies(ch.labels().size(), ch.colors().isEmpty() ? "accent" : ch.colors().get(0));
                    List<Object> hex = new ArrayList<>();
                    cols.forEach(x -> hex.add("#" + Palette.PALETTE.getOrDefault(x, Palette.PALETTE.get("accent"))));
                    charts.add(D.of("id", sec.key() + "-" + ch.key(), "kind", ch.kind(), "labels", ch.labels(), "values", ch.values(),
                            "colors", hex, "unit", ch.unit()));
                }
            }
            Environment env = new Environment("com/legacyaudit/reports/summary_html")
                    .global("fmt", args -> num(args.get(0)));
            String html = env.render("summary.html", D.of("app", app, "run", rd.run, "target_label", D.get(rd.target, "label", ""),
                    "sections", sections, "palette", Palette.PALETTE, "chart_js", "chart-4.4.7.umd.js", "charts", charts));
            Files.writeString(out.resolve("index.html"), html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
