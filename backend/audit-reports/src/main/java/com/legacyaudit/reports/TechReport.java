package com.legacyaudit.reports;

import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.engine.store.FactWriter;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.reports.jinja.Environment;
import com.legacyaudit.reports.jinja.Runtime;
import com.legacyaudit.reports.jinja.Values;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code legacy-audit report --type tech}: a static, self-contained HTML site built from one run.
 *
 * <p>Pages are rendered from {@code tech_html/} (the Python tool's Jinja templates, unchanged);
 * catalogues are written as {@code data/*.js} and drawn by {@code assets/report.js}; Chart.js and
 * Mermaid are vendored in {@code assets/vendor/}. The site opens from disk ({@code file://}) with no
 * server and no network. It is written to {@code runs/<id>/reports/tech/}; when {@code latest} is a
 * copy of that run (no symbolic links on the machine), the copy gets its own build so its relative
 * links stay right.
 */
public final class TechReport {

    static final List<String> SEVERITIES = List.of("critical", "high", "medium", "low", "info");
    static final Map<String, String> SEVERITY_COLOR = Map.of(
            "critical", "critical", "high", "serious", "medium", "warning", "low", "info", "info", "info");
    static final List<String> GRADES = List.of("auto", "review", "manual");
    static final Map<String, String> GRADE_COLOR = Map.of("auto", "good", "review", "warning", "manual", "critical");
    static final Pattern COPYLEFT = Rx.of("\\b(A?GPL|LGPL|EPL|MPL|CDDL|EUPL|CC-BY-SA)", Rx.I);
    static final Pattern SECRET_LINE = Rx.of(
            "^(\\s*[\\w.-]*(?:passw(?:or)?d|pwd|passphrase|secret|api[_-]?key|token|credential)[\\w.-]*\\s*[:=]\\s*)(\\S.*)$",
            Rx.I | Rx.M);
    static final Pattern LOG_LINE = Rx.of("^\\S+ \\S+ (DEBUG|INFO|WARNING|ERROR|CRITICAL) ");
    static final List<List<String>> NAV = List.of(
            List.of("overview", "index.html", "Overview"),
            List.of("components", "components.html", "Components"),
            List.of("architecture", "architecture.html", "Architecture"),
            List.of("flows", "flows.html", "Flows"),
            List.of("data-model", "data-model.html", "Data model"),
            List.of("services", "services.html", "Services"),
            List.of("screens", "screens.html", "Screens"),
            List.of("sql", "sql.html", "SQL"),
            List.of("db-objects", "db-objects.html", "Database"),
            List.of("integrations", "integrations.html", "Integrations"),
            List.of("web-services", "web-services.html", "Web services"),
            List.of("batch-jobs", "batch-jobs.html", "Batch jobs"),
            List.of("libraries", "libraries.html", "Libraries"),
            List.of("postgresql", "postgresql.html", "PostgreSQL"),
            List.of("security", "security.html", "Security"),
            List.of("findings", "findings.html", "Findings"),
            List.of("analysis", "analysis.html", "Analysis"),
            List.of("appendix", "appendix.html", "Appendix"));
    static final Map<String, List<String>> CATALOGUE_PAGES = new LinkedHashMap<>();

    static {
        CATALOGUE_PAGES.put("services", List.of("services"));
        CATALOGUE_PAGES.put("screens", List.of("screens"));
        CATALOGUE_PAGES.put("sql", List.of("sql"));
        CATALOGUE_PAGES.put("db-objects", List.of("db-objects", "crud"));
        CATALOGUE_PAGES.put("integrations", List.of("integrations"));
        CATALOGUE_PAGES.put("web-services", List.of("web-services", "web-operations"));
        CATALOGUE_PAGES.put("batch-jobs", List.of("batches", "batch-jobs"));
        CATALOGUE_PAGES.put("libraries", List.of("libraries"));
    }

    static final List<Values.Tuple> GLOSSARY = List.of(
            RunData.tuple("Service", "An independently invokable operation: Struts action, Spring handler, servlet, "
                    + "web service operation, CICS transaction, batch job."),
            RunData.tuple("Screen", "A rendered page or map (JSP/JSF/HTML view, BMS map); partials are included "
                    + "fragments, not counted."),
            RunData.tuple("Component", "A buildable or deployable unit found from build files and folders."),
            RunData.tuple("Integration", "One external system reached in one direction: database, queue, host, file drop, partner."),
            RunData.tuple("Web service", "A REST or SOAP service, listed once with its operations: offered (other systems call "
                    + "the application) or called (the application calls another system)."),
            RunData.tuple("PG grade", "auto: converts to PostgreSQL cleanly; review: vendor syntax to check; manual: "
                    + "stored code, unparseable SQL, or SQL whose structure is only known at run time."),
            RunData.tuple("Value hole", "A value concatenated into SQL text at run time (?): bind it; injection risk."),
            RunData.tuple("Structural hole", "A table, column or clause only known at run time (?): the statement "
                    + "cannot be converted by a script."),
            RunData.tuple("Evidence", "The file and line every fact was found at; links open the file, or VS Code at the line."),
            RunData.tuple("Coverage gap", "An input that was not provided or a tool that did not run; reported, never "
                    + "treated as 'nothing found'."),
            RunData.tuple("Dead-code candidate", "A source file nothing reachable uses; candidates only, since "
                    + "reflection and outside callers are invisible to a scan."));

    /** KPI tile to (icon, colour class); colours follow the section the tile links to. */
    static final Map<String, List<String>> TILE_STYLE = new LinkedHashMap<>();
    static final Map<String, List<String>> COMPONENT_TILE_STYLE = new LinkedHashMap<>();

    static {
        String[][] tiles = {
            {"totals.files", "files", "c1"}, {"totals.loc_code", "code", "c1"}, {"totals.components", "components", "c3"},
            {"counts.services.total", "services", "c7"}, {"counts.screens.total", "screens", "c1"},
            {"counts.sql.total", "sql", "c6"}, {"counts.pg_grades.manual", "warning", "c8"},
            {"counts.stored_procedures", "procedure", "c6"}, {"counts.integrations.total", "integrations", "c2"},
            {"counts.web_services.total", "integrations", "c2"}, {"counts.batch_jobs.total", "batch", "c4"},
            {"counts.libraries.total", "libraries", "c5"}, {"counts.vulnerable_libraries.total", "shield", "c8"},
            {"counts.eol_libraries", "eol", "c8"}, {"totals.findings", "findings", "c2"}, {"effort.likely", "effort", "c7"}};
        for (String[] t : tiles) {
            TILE_STYLE.put(t[0], List.of(t[1], t[2]));
        }
        String[][] comp = {
            {"component.files", "files", "c1"}, {"component.loc_code", "code", "c1"}, {"component.services", "services", "c7"},
            {"component.screens", "screens", "c1"}, {"component.sql", "sql", "c6"},
            {"component.integrations", "integrations", "c2"}, {"component.jobs", "batch", "c4"},
            {"component.findings", "findings", "c2"}};
        for (String[] t : comp) {
            COMPONENT_TILE_STYLE.put(t[0], List.of(t[1], t[2]));
        }
    }

    static final List<String> ASSETS = List.of(
            "report.css", "report.js", "vendor/LICENSE-chartjs.md", "vendor/LICENSE-mermaid.txt", "vendor/chart-4.4.7.umd.js",
            "vendor/mermaid-11.4.1.min.js");

    private TechReport() {}

    static Environment env() {
        return new Environment("com/legacyaudit/reports/tech_html")
                .global("icon", args -> Visuals.icon(Values.str(args.get(0))));
    }

    /** Build the site for a run; returns {@code reports/tech/index.html} of that run. */
    public static Path build(Path runDir, Path inputsDir) {
        RunData rd = RunData.load(runDir, inputsDir);
        Path out = runDir.resolve("reports").resolve("tech");
        buildInto(rd, out);
        Path latest = latestCopyOf(runDir, rd);
        if (latest != null) {
            buildInto(rd, latest.resolve("reports").resolve("tech"));
        }
        return out.resolve("index.html");
    }

    /** {@code <output>/latest} when it is a plain copy of this run (not a symbolic link), else null. */
    static Path latestCopyOf(Path runDir, RunData rd) {
        Path parent = runDir.toAbsolutePath().getParent();
        Path latest = parent == null || parent.getParent() == null ? null : parent.getParent().resolve("latest");
        if (latest == null || !Files.isDirectory(latest) || Files.isSymbolicLink(latest)) {
            return null;
        }
        try {
            if (RunData.resolve(latest).equals(RunData.resolve(runDir))) {
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        Object run = Json.load(latest.resolve("run.json"));
        if (run instanceof Map<?, ?> m && m.get("run_id") != null && m.get("run_id").equals(rd.run.get("run_id"))) {
            return latest;
        }
        return null;
    }

    /** The site of a run into a folder (emptied first). */
    public static void buildInto(RunData rd, Path out) {
        try {
            Fmt.rmtree(out);
            Files.createDirectories(out.resolve("data"));
            Fmt.copyResources("com/legacyaudit/reports/assets", ASSETS, out.resolve("assets"));
            Map<String, Object> e = Status.loadEnrichment(rd.runDir);
            Map<String, Object> fn = D.map(e, "functional");
            Map<String, Map<String, Object>> stories = new LinkedHashMap<>();
            for (Map<String, Object> i : D.maps(fn, "interfaces")) {
                stories.put(D.str(i, "integration_id"), i);
            }
            stories = rd.byRow(stories);
            Map<String, Map<String, Object>> wsStories = new LinkedHashMap<>();
            for (Map<String, Object> w : D.maps(fn, "web_services")) {
                wsStories.put(D.str(w, "web_service_id"), w);
            }
            Map<String, Catalogues.Catalogue> cats = Catalogues.build(rd, out, stories, wsStories);
            if (rd.auditOnly()) { // no target database: no PostgreSQL grades
                for (String name : List.of("sql", "db-objects")) {
                    Catalogues.Catalogue c = cats.get(name);
                    if (c != null) {
                        c.drop("grade");
                        cats.put(name, c.withIntro(c.intro().replace(", with its PostgreSQL grade", "")));
                    }
                }
            }
            for (Catalogues.Catalogue c : cats.values()) {
                writeDataJs(out.resolve("data").resolve(c.name() + ".js"), c.name(), c.payload());
            }
            if (rd.hasSbom) {
                Files.copy(rd.runDir.resolve("data").resolve("sbom.cdx.json"), out.resolve("sbom.cdx.json"));
            }
            Pages page = new Pages(rd, out, cats, env(), e);
            cats.put("batches", Catalogues.batches(rd, out, page.batchRows));
            writeDataJs(out.resolve("data").resolve("batches.js"), "batches", cats.get("batches").payload());
            writeBatchesCsv(out.resolve("data").resolve("batches.csv"), page.batchRows);
            writeCatalogueCsv(out.resolve("data").resolve("web-services.csv"), cats.get("web-services"));
            page.overview();
            page.components();
            page.architecture();
            page.flows();
            page.dataModel();
            for (Map.Entry<String, List<String>> p : CATALOGUE_PAGES.entrySet()) {
                List<Catalogues.Catalogue> list = new ArrayList<>();
                p.getValue().forEach(n -> list.add(cats.get(n)));
                page.catalogue(p.getKey(), list);
            }
            if (!rd.auditOnly()) {
                page.postgresql();
            }
            page.security();
            page.findings();
            page.analysis();
            page.appendix();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String csvCell(Object v) {
        return v == null ? "" : Py.str(v);
    }

    /** The consolidated batch list for Excel (UTF-8 with BOM so Excel reads accents). */
    static void writeBatchesCsv(Path path, List<Map<String, Object>> rows) throws IOException {
        List<String> cols = List.of("name", "kind", "component", "what", "what_from", "when", "when_from", "scheduler", "steps",
                "when_file", "when_line");
        StringBuilder sb = new StringBuilder("﻿");
        FactWriter.csvRow(sb, cols);
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            cols.forEach(c -> cells.add(csvCell(r.get(c))));
            FactWriter.csvRow(sb, cells);
        }
        Files.writeString(path, sb, StandardCharsets.UTF_8);
    }

    /** A catalogue as CSV for Excel: links and evidence become their text. */
    static void writeCatalogueCsv(Path path, Catalogues.Catalogue c) throws IOException {
        StringBuilder sb = new StringBuilder("﻿");
        List<String> head = new ArrayList<>();
        c.columns().forEach(col -> head.add(col.label()));
        FactWriter.csvRow(sb, head);
        for (List<Object> row : c.rows()) {
            List<String> cells = new ArrayList<>();
            for (Object v : row) {
                cells.add(csvCell(v instanceof Map<?, ?> m ? (m.containsKey("text") ? m.get("text") : "") : v));
            }
            FactWriter.csvRow(sb, cells);
        }
        Files.writeString(path, sb, StandardCharsets.UTF_8);
    }

    static void writeDataJs(Path path, String name, Object payload) throws IOException {
        String body = Json.dumps(payload, false, false, ",", ":").replace("</", "<\\/");
        Files.writeString(path, "window.LA_DATA=window.LA_DATA||{};window.LA_DATA[" + Json.dumps(name, true, false, ", ", ": ") + "]="
                + body + ";\n", StandardCharsets.UTF_8);
    }

    /** A one-series bar chart ({@code colors}: CSS token names, one per bar or one for all). */
    static Map<String, Object> bar(String id, String title, List<?> labels, List<?> values, Object colors, String label,
            String category, boolean horizontal, String note) {
        List<Object> cols = new ArrayList<>();
        if (colors instanceof String s) {
            labels.forEach(l -> cols.add(s));
        } else {
            cols.addAll((List<?>) colors);
        }
        Map<String, Object> spec = D.of("labels", labels, "values", values, "colors", cols, "label", label, "unit", "",
                "horizontal", horizontal);
        return D.of("id", id, "title", title, "labels", labels, "values", values, "label", label,
                "category", category.isEmpty() ? title : category, "spec", spec, "note", note);
    }

    /** One pass over a run: the shared context of every page and one method per page. */
    static final class Pages {
        final RunData rd;
        final Path out;
        final Map<String, Catalogues.Catalogue> cats;
        final Environment env;
        final Map<String, Object> counts;
        final Map<String, Object> e;
        final List<Flows.Flow> screenFlows;
        final List<Flows.Flow> batchFlows;
        final Map<String, List<Map<String, Object>>> batchComments = new LinkedHashMap<>();
        final Map<String, String> batchSaid = new LinkedHashMap<>();
        final List<Map<String, Object>> batchRows;
        final Map<String, Object> common = new LinkedHashMap<>();

        Pages(RunData rd, Path out, Map<String, Catalogues.Catalogue> cats, Environment env, Map<String, Object> e) {
            this.rd = rd;
            this.out = out;
            this.cats = cats;
            this.env = env;
            this.e = e;
            Map<String, Object> s = rd.summary;
            counts = D.map(s, "counts");
            screenFlows = Flows.screenFlows(rd);
            batchFlows = Flows.batchFlows(rd);
            for (Flows.Flow f : batchFlows) {
                batchComments.put(f.id(), Flows.codeComments(rd, f));
            }
            batchComments.forEach((fid, c) -> { // first line of the code's own comment, for the batch cards
                if (!c.isEmpty()) {
                    batchSaid.put(fid, D.strings(c.get(0), "lines").get(0));
                }
            });
            Map<String, Map<String, Object>> stories = new LinkedHashMap<>();
            for (Map<String, Object> b : D.maps(D.map(e, "functional"), "batches")) {
                stories.put(D.str(b, "job_id"), b);
            }
            batchRows = Batches.batchList(rd, batchFlows, batchSaid, stories);
            Map<String, Object> run = rd.run;
            Object app = D.or(D.or(D.get(run, "application"), D.get(D.map(s, "run"), "application")), "application");
            common.put("app", app);
            common.put("run_id", D.get(run, "run_id", ""));
            String started = D.s(run, "started_at");
            common.put("run_date", started.substring(0, Math.min(16, started.length())).replace("T", " "));
            common.put("tool_version", D.get(run, "tool_version", ""));
            common.put("sources", D.get(run, "sources", new ArrayList<>()));
            common.put("badges", badges());
            List<Object> nav = new ArrayList<>();
            for (List<String> n : NAV) {
                if (!(n.get(0).equals("postgresql") && rd.auditOnly())) {
                    nav.add(D.of("id", n.get(0), "href", n.get(1), "title", n.get(2)));
                }
            }
            common.put("nav", nav);
            common.put("target_label", D.get(rd.target, "label", ""));
            common.put("audit_only", rd.auditOnly());
            common.put("effort_label", D.get(rd.effort == null ? Map.of() : rd.effort, "label", "estimate"));
            common.put("doc_kind", "Technical audit");
        }

        private List<Object> badges() {
            List<Object> res = new ArrayList<>();
            for (Map<String, Object> c : D.maps(rd.run, "coverage")) {
                Object kind = D.get(c, "kind");
                if ("dependency-check".equals(kind) || "osv-scanner".equals(kind)) {
                    continue;
                }
                Object n = D.get(c, "file_count", 0L);
                Object status = D.get(c, "status", "missing");
                String text = "present".equals(status) ? D.str(c, "label") + ": " + Py.str(n) + " file(s)" : D.str(c, "label") + ": not provided";
                res.add(D.of("status", status, "text", text, "title", D.get(c, "path", "")));
            }
            for (Map<String, Object> v : D.maps(rd.run, "vulnerability_sources")) {
                String raw = D.str(v, "name");
                String name = raw.equals("dependency-check") ? "Dependency-Check" : raw.equals("osv-scanner") ? "OSV-Scanner" : raw;
                Object status = D.get(v, "status");
                if ("imported".equals(status) || "ran".equals(status)) {
                    Object date = D.get(v, "database_date");
                    String text = name + ": " + Py.str(status) + (Py.truthy(date) ? ", database " + Py.str(date) : "");
                    res.add(D.of("status", "present", "text", text, "title", ""));
                } else {
                    res.add(D.of("status", "missing", "text", name + ": " + Py.str(D.or(D.get(v, "detail"), status)), "title", ""));
                }
            }
            return res;
        }

        void render(String template, String path, String pageId, String title, Map<String, Object> ctx) {
            Map<String, Object> vars = new LinkedHashMap<>(common);
            vars.put("base", "../".repeat((int) path.chars().filter(c -> c == '/').count()));
            vars.put("page_id", pageId);
            vars.put("page_title", title);
            vars.put("data_scripts", ctx.containsKey("data_scripts") ? ctx.remove("data_scripts") : new ArrayList<>());
            vars.put("charts", ctx.containsKey("charts") ? ctx.remove("charts") : false);
            vars.put("diagrams", ctx.containsKey("diagrams") ? ctx.remove("diagrams") : false);
            vars.putAll(ctx);
            String html = env.render(template, vars);
            Path target = out.resolve(path);
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, html, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        Map<String, Object> link(Object file, Object line) {
            return rd.evidenceLinks(out, file, line);
        }

        Map<String, Object> link(List<?> fileLine) {
            return rd.evidenceLinks(out, fileLine.get(0), fileLine.get(1));
        }

        Map<String, Object> tile(String key, String label, String href, String sub) {
            Object value = Fmt.at(rd.summary, key);
            List<String> style = TILE_STYLE.getOrDefault(key, List.of("files", "c1"));
            return D.of("key", key, "label", label, "value", value, "display", Fmt.num(value), "href", href, "sub", sub,
                    "icon", style.get(0), "color", style.get(1));
        }

        private String f(String path) {
            return Fmt.num(Fmt.at(counts, path));
        }

        // ---- pages ----------------------------------------------------------------------------

        void overview() {
            Map<String, Object> s = rd.summary;
            Map<String, Object> effort = rd.effort == null ? Map.of() : rd.effort;
            List<Object> tiles = new ArrayList<>();
            tiles.add(tile("totals.files", "Files", "", Fmt.num(Fmt.at(s, "totals.input_files")) + " input files"));
            tiles.add(tile("totals.loc_code", "Code lines", "", Fmt.num(Fmt.at(s, "totals.loc_comment")) + " comment lines"));
            tiles.add(tile("totals.components", "Components", "components.html", ""));
            tiles.add(tile("counts.services.total", "Services", "services.html", ""));
            tiles.add(tile("counts.screens.total", "Screens", "screens.html", f("screens.partials") + " partials not counted"));
            tiles.add(tile("counts.sql.total", "SQL statements", "sql.html",
                    f("sql.distinct") + " distinct, " + f("sql.dynamic") + " dynamic"));
            if (!rd.auditOnly()) {
                tiles.add(tile("counts.pg_grades.manual", "SQL needing manual work", "postgresql.html", ""));
            }
            tiles.add(tile("counts.stored_procedures", "Stored procedures", rd.auditOnly() ? "db-objects.html" : "postgresql.html", ""));
            tiles.add(tile("counts.integrations.total", "Integrations", "integrations.html", ""));
            tiles.add(tile("counts.web_services.total", "Web services", "web-services.html",
                    f("web_services.offered") + " offered, " + f("web_services.called") + " called"));
            tiles.add(tile("counts.batch_jobs.total", "Batch jobs", "batch-jobs.html",
                    f("batch_jobs.scheduled") + " scheduled, " + f("batch_jobs.unscheduled") + " with no schedule found"));
            tiles.add(tile("counts.libraries.total", "Libraries", "libraries.html", f("libraries.undeclared") + " undeclared"));
            tiles.add(tile("counts.vulnerable_libraries.total", "Vulnerable libraries", "security.html",
                    f("vulnerable_libraries.vulnerabilities") + " vulnerabilities"));
            tiles.add(tile("counts.eol_libraries", "End-of-life libraries", "security.html", ""));
            tiles.add(tile("totals.findings", "Findings", "findings.html", ""));
            if (!effort.isEmpty()) {
                tiles.add(tile("effort.likely", "Effort, likely (person-days)", "",
                        Fmt.num(effort.get("low")) + " to " + Fmt.num(effort.get("high"))));
            }
            List<Values.Tuple> langs = new ArrayList<>();
            D.map(s, "by_language").forEach((k, v) -> langs.add(RunData.tuple(k, D.get(v, "loc_code", 0L))));
            langs.sort((a, b) -> Double.compare(D.num(b.get(1)), D.num(a.get(1))));
            langs.removeIf(kv -> D.num(kv.get(1)) <= 0);
            List<Values.Tuple> top = new ArrayList<>(langs.subList(0, Math.min(7, langs.size())));
            if (langs.size() > 7) {
                long other = 0;
                for (Values.Tuple kv : langs.subList(7, langs.size())) {
                    other += ((Number) kv.get(1)).longValue();
                }
                top.add(RunData.tuple("other", other));
            }
            List<Object> labels = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            List<Object> colors = new ArrayList<>();
            for (int i = 0; i < top.size(); i++) {
                labels.add(top.get(i).get(0));
                values.add(top.get(i).get(1));
                colors.add("series-" + (i % 8 + 1));
            }
            Map<String, Object> locChart = new Visuals.Chart("loc", "Code lines by language", "doughnut", labels).values(values)
                    .colors(colors).label("Code lines").category("Language").unit("lines")
                    .note(langs.size() > 7 ? "Top 7 languages; the rest are grouped as 'other'." : "").build();
            Map<String, Map<String, Object>> metrics = new LinkedHashMap<>();
            for (Map<String, Object> m : D.maps(s, "component_metrics")) {
                metrics.put(D.str(m, "component"), m);
            }
            List<Object> comps = new ArrayList<>();
            for (Map<String, Object> comp : rd.items("components")) {
                Map<String, Object> m = metrics.getOrDefault(D.str(comp, "name"), Map.of());
                comps.add(D.with(comp, "slug", RunData.slug(D.str(comp, "name")), "complexity", m.get("complexity"),
                        "duplication_pct", m.get("duplication_pct"), "test_ratio", m.get("test_ratio"),
                        "dead_code_candidates", m.get("dead_code_candidates")));
            }
            List<Object> largest = new ArrayList<>();
            for (Map<String, Object> fl : D.maps(s, "largest_files")) {
                largest.add(D.with(fl, "link", link(fl.get("path"), null)));
            }
            List<Map<String, Object>> stack = new ArrayList<>(rd.items("tech_stack"));
            stack.sort((a, b) -> {
                int c = PyJson.compareCodePoints(D.s(a, "category"), D.s(b, "category"));
                return c != 0 ? c : PyJson.compareCodePoints(D.str(a, "name"), D.str(b, "name"));
            });
            List<Object> servers = new ArrayList<>();
            for (Map<String, Object> r : Servers.serverRows(rd, e)) {
                List<Object> links = new ArrayList<>();
                Map<String, Object> lines = D.map(r, "lines");
                for (String fl : D.strings(r, "files")) {
                    links.add(link(fl, lines.get(fl)));
                }
                servers.add(D.with(r, "links", links));
            }
            render("overview.html", "index.html", "overview", "Overview", D.of("tiles", tiles, "loc_chart", locChart,
                    "band_chart", bandChart(), "gauges", gauges(), "tech_stack", stack, "servers", servers, "components", comps,
                    "effort", effort.isEmpty() ? null : effort, "largest_files", largest, "charts", true));
        }

        /** Items (services, screens, jobs, units) per complexity band, per component. */
        Map<String, Object> bandChart() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> m : rd.items("metrics")) {
                if (D.t(m, "bands")) {
                    rows.add(m);
                }
            }
            rows.sort((a, b) -> Double.compare(sum(D.map(b, "bands")), sum(D.map(a, "bands"))));
            rows = rows.subList(0, Math.min(15, rows.size()));
            List<Object> labels = new ArrayList<>();
            rows.forEach(m -> labels.add(m.get("component")));
            List<Map<String, Object>> datasets = new ArrayList<>();
            for (String band : Visuals.COMPLEXITY_BANDS) {
                List<Object> vals = new ArrayList<>();
                rows.forEach(m -> vals.add((long) D.num(D.get(D.map(m, "bands"), band, 0L))));
                datasets.add(D.of("label", band, "values", vals, "color", Visuals.BAND_COLOR.get(band)));
            }
            return new Visuals.Chart("bands", "Complexity bands per component", "stacked", labels).datasets(datasets)
                    .category("Component").unit("items").horizontal(true)
                    .note("Services, screens and batch jobs per complexity band (weights in the "
                            + "configuration). Largest 15 components.")
                    .build();
        }

        private static double sum(Map<String, Object> m) {
            double t = 0;
            for (Object v : m.values()) {
                t += D.num(v);
            }
            return t;
        }

        List<Object> gauges() {
            long sqlTotal = Fmt.n(counts, "sql.total");
            long libs = Fmt.n(counts, "libraries.total");
            List<Map<String, Object>> cov = D.maps(rd.run, "coverage");
            long present = cov.stream().filter(x -> "present".equals(D.get(x, "status"))).count();
            List<Object> res = new ArrayList<>();
            res.add(Visuals.gauge("SQL that converts automatically", Fmt.n(counts, "pg_grades.auto"), sqlTotal, "good",
                    f("pg_grades.auto") + " of " + Fmt.num(sqlTotal) + " statements"));
            res.add(Visuals.gauge("Libraries with no known vulnerability", libs - Fmt.n(counts, "vulnerable_libraries.total"), libs,
                    "series-1", f("vulnerable_libraries.total") + " vulnerable of " + Fmt.num(libs)));
            res.add(Visuals.gauge("Libraries still supported", libs - Fmt.n(counts, "eol_libraries"), libs, "series-3",
                    f("eol_libraries") + " end of life"));
            res.add(Visuals.gauge("Inputs provided", present, (long) cov.size(), "series-7", present + " of " + cov.size() + " kinds"));
            return res;
        }

        void components() {
            Map<String, Map<String, Long>> per = new LinkedHashMap<>();
            java.util.function.Function<String, Map<String, Long>> perOf = c -> per.computeIfAbsent(c, k -> new LinkedHashMap<>());
            String[][] kinds = {{"services", "services"}, {"screens", "screens"}, {"sql_statements", "sql"}, {"batch_jobs", "jobs"}};
            for (String[] k : kinds) {
                for (Map<String, Object> item : rd.items(k[0])) {
                    if (D.t(item, "component")) {
                        perOf.apply(D.str(item, "component")).merge(k[1], 1L, Long::sum);
                    }
                }
            }
            for (Map<String, Object> item : rd.items("integrations")) {
                for (String comp : D.strings(item, "components")) {
                    perOf.apply(comp).merge("integrations", 1L, Long::sum);
                }
            }
            for (Map<String, Object> fd : rd.items("findings")) {
                String comp = rd.fileComponent.get(D.s(fd, "file"));
                if (comp != null && !comp.isEmpty()) {
                    perOf.apply(comp).merge("findings", 1L, Long::sum);
                }
            }
            Map<List<String>, Map<String, Long>> edges = new LinkedHashMap<>();
            for (Map<String, Object> ed : rd.items("dependencies")) {
                String a = rd.componentOf(D.str(ed, "source"));
                String b = rd.componentOf(D.str(ed, "target"));
                if (a != null && b != null && !a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
                    edges.computeIfAbsent(List.of(a, b), k -> new LinkedHashMap<>()).merge(D.str(ed, "kind"), 1L, Long::sum);
                }
            }
            Map<String, List<Object>> libs = new LinkedHashMap<>();
            for (Map<String, Object> lib : rd.items("libraries")) {
                Set<String> usedIn = new TreeSet<>(PyJson::compareCodePoints);
                for (String w : D.strings(lib, "where_used")) {
                    usedIn.add(rd.fileComponent.getOrDefault(w, ""));
                }
                usedIn.remove("");
                for (String comp : usedIn) {
                    libs.computeIfAbsent(comp, k -> new ArrayList<>()).add(D.of("name", Catalogues.libraryName(lib),
                            "version", D.s(lib, "version"), "action", D.get(D.map(lib, "recommendation"), "action", ""),
                            "cves", (long) D.list(lib, "cves").size(), "eol", D.get(lib, "eol")));
                }
            }
            Map<String, Map<String, Object>> radar = radars(per);
            Map<String, Object> docs = new LinkedHashMap<>();
            for (Map<String, Object> d : D.maps(D.map(e, "component_docs"), "components")) {
                List<Object> links = new ArrayList<>();
                for (Map<String, Object> ev : D.maps(d, "evidence")) {
                    links.add(link(D.get(ev, "file"), D.get(ev, "line")));
                }
                List<Object> elements = new ArrayList<>();
                for (Map<String, Object> k : D.maps(d, "key_elements")) {
                    elements.add(D.with(k, "link", D.t(k, "file") ? link(k.get("file"), null) : null));
                }
                List<Object> tables = new ArrayList<>();
                for (String i : D.strings(d, "db_object_ids")) {
                    tables.add(rd.label(i));
                }
                docs.put(D.str(d, "component"), D.with(d, "links", links, "elements", elements, "tables", tables));
            }
            List<Object> rows = new ArrayList<>();
            for (Map<String, Object> comp : rd.items("components")) {
                String name = D.str(comp, "name");
                Map<String, Long> n = perOf.apply(name);
                rows.add(D.with(comp, "slug", RunData.slug(name), "n_services", n.getOrDefault("services", 0L),
                        "n_screens", n.getOrDefault("screens", 0L), "n_sql", n.getOrDefault("sql", 0L),
                        "n_findings", n.getOrDefault("findings", 0L)));
                List<Map<String, Object>> tiles = new ArrayList<>();
                tiles.add(D.of("key", "component.files", "label", "Files", "value", D.get(comp, "files", 0L)));
                tiles.add(D.of("key", "component.loc_code", "label", "Code lines", "value", D.get(comp, "loc_code", 0L)));
                tiles.add(D.of("key", "component.services", "label", "Services", "value", n.getOrDefault("services", 0L)));
                tiles.add(D.of("key", "component.screens", "label", "Screens", "value", n.getOrDefault("screens", 0L)));
                tiles.add(D.of("key", "component.sql", "label", "SQL statements", "value", n.getOrDefault("sql", 0L)));
                tiles.add(D.of("key", "component.integrations", "label", "Integrations", "value", n.getOrDefault("integrations", 0L)));
                tiles.add(D.of("key", "component.jobs", "label", "Batch jobs", "value", n.getOrDefault("jobs", 0L)));
                tiles.add(D.of("key", "component.findings", "label", "Findings", "value", n.getOrDefault("findings", 0L)));
                for (Map<String, Object> t : tiles) {
                    t.put("display", Fmt.num(t.get("value")));
                    t.put("sub", "");
                    List<String> st = COMPONENT_TILE_STYLE.get(Py.str(t.get("key")));
                    t.put("icon", st.get(0));
                    t.put("color", st.get(1));
                }
                List<Object> entry = new ArrayList<>();
                for (Map<String, Object> ep : rd.items("entry_points")) {
                    if (name.equals(D.get(ep, "component"))) {
                        entry.add(D.with(ep, "link", link(rd.firstEvidence(ep))));
                    }
                }
                List<Object> flowList = new ArrayList<>();
                List<Flows.Flow> all = new ArrayList<>(screenFlows);
                all.addAll(batchFlows);
                for (Flows.Flow fl : all) {
                    if (name.equals(fl.component())) {
                        flowList.add(D.of("name", fl.name(), "slug", fl.slug(), "kind", fl.kind(), "summary", fl.summary()));
                    }
                }
                Map<String, Map<String, Long>> outs = new TreeMap<>(PyJson::compareCodePoints);
                Map<String, Map<String, Long>> ins = new TreeMap<>(PyJson::compareCodePoints);
                edges.forEach((k, v) -> {
                    if (k.get(0).equals(name)) {
                        outs.put(k.get(1), v);
                    }
                    if (k.get(1).equals(name)) {
                        ins.put(k.get(0), v);
                    }
                });
                Map<String, Object> ctx = new LinkedHashMap<>(comp);
                ctx.put("tiles", tiles);
                ctx.put("radar", radar.get(name));
                ctx.put("doc", docs.get(name));
                ctx.put("flows", flowList);
                ctx.put("entry_points", entry);
                ctx.put("deps_out", deps(outs));
                ctx.put("deps_in", deps(ins));
                ctx.put("libraries", libs.getOrDefault(name, new ArrayList<>()));
                render("component.html", "components/" + RunData.slug(name) + ".html", "components", "Component: " + name,
                        D.of("c", ctx, "data_scripts", new ArrayList<>(List.of("services", "screens", "sql", "integrations",
                                "web-services", "batch-jobs", "findings")), "charts", true));
            }
            render("components.html", "components.html", "components", "Components", D.of("components", rows));
        }

        private static List<Object> deps(Map<String, Map<String, Long>> pairs) {
            List<Object> res = new ArrayList<>();
            pairs.forEach((o, k) -> {
                long count = 0;
                for (long v : k.values()) {
                    count += v;
                }
                Set<String> kinds = new TreeSet<>(PyJson::compareCodePoints);
                kinds.addAll(k.keySet());
                res.add(D.of("name", o, "slug", RunData.slug(o), "count", count, "kinds", String.join(", ", kinds)));
            });
            return res;
        }

        /**
         * Each component's profile against the largest value of each axis in this estate (100 = the
         * largest); the raw values are in the tooltip and the table view.
         */
        Map<String, Map<String, Object>> radars(Map<String, Map<String, Long>> per) {
            Map<String, Map<String, Object>> metrics = new LinkedHashMap<>();
            for (Map<String, Object> m : rd.items("metrics")) {
                metrics.put(D.str(m, "component"), m);
            }
            List<String> axes = List.of("Code lines", "Complexity", "Fan-in", "Fan-out", "Duplication %", "Findings", "SQL");
            Map<String, double[]> raw = new LinkedHashMap<>();
            for (Map<String, Object> c : rd.items("components")) {
                String name = D.str(c, "name");
                Map<String, Object> m = metrics.getOrDefault(name, Map.of());
                Map<String, Long> p = per.getOrDefault(name, Map.of());
                raw.put(name, new double[] {D.num(D.get(c, "loc_code", 0L)), D.num(D.or(m.get("complexity_score"), 0L)),
                    D.num(D.or(m.get("fan_in_max"), 0L)), D.num(D.or(m.get("fan_out_max"), 0L)),
                    D.num(D.or(m.get("duplication_pct"), 0L)), p.getOrDefault("findings", 0L), p.getOrDefault("sql", 0L)});
            }
            double[] tops = new double[axes.size()];
            for (double[] r : raw.values()) {
                for (int i = 0; i < tops.length; i++) {
                    tops[i] = Math.max(tops[i], r[i]);
                }
            }
            Map<String, Map<String, Object>> res = new LinkedHashMap<>();
            raw.forEach((name, vals) -> {
                List<Object> pct = new ArrayList<>();
                List<Object> shown = new ArrayList<>();
                for (int i = 0; i < vals.length; i++) {
                    pct.add(tops[i] != 0 ? (long) Math.rint(100 * vals[i] / tops[i]) : 0L);
                    double v = vals[i];
                    shown.add(Fmt.num(v == Math.rint(v) && !Double.isInfinite(v) ? (Object) (long) v : (Object) v));
                }
                res.put(name, new Visuals.Chart("radar", "Profile against the other components", "radar", axes).values(pct)
                        .label(name).category("Measure").extra("color", "series-3").extra("raw", shown)
                        .note("100 = the highest value of that measure among the components; "
                                + "the table shows the measured values.")
                        .build());
            });
            return res;
        }

        void architecture() {
            render("architecture.html", "architecture.html", "architecture", "Architecture and flows", D.of(
                    "components_diagram", Diagrams.componentGraph(rd), "integration_diagram", Diagrams.integrationMap(rd),
                    "batch_diagrams", Diagrams.batchChains(rd), "layers_diagram", Diagrams.layered(D.map(e, "architecture")),
                    "target_diagram", Diagrams.currentToTarget(rd, D.map(e, "target_architecture")), "diagrams", true));
        }

        /** What a flow page shows: the scan's flow, plus the agent's story when there is one. */
        Map<String, Object> flowCtx(Flows.Flow f, Map<String, Object> story) {
            List<Object> steps = new ArrayList<>();
            for (Map<String, Object> st : f.steps()) {
                steps.add(D.with(st, "link", link((List<?>) st.get("evidence")), "label", st.get("name")));
            }
            if (story != null) {
                List<Object> links = new ArrayList<>();
                for (Map<String, Object> ev : D.maps(story, "evidence")) {
                    links.add(link(D.get(ev, "file"), D.get(ev, "line")));
                }
                List<Map<String, Object>> sorted = new ArrayList<>(D.maps(story, "steps"));
                sorted.sort((a, b) -> Values.compare(D.get(a, "order", 0L), D.get(b, "order", 0L)));
                List<Object> stepList = new ArrayList<>();
                for (Map<String, Object> st : sorted) {
                    List<Object> facts = new ArrayList<>();
                    for (String i : D.strings(st, "fact_ids")) {
                        facts.add(D.last(rd.label(i), "/"));
                    }
                    stepList.add(D.with(st, "facts", facts));
                }
                story = D.with(story, "links", links, "step_list", stepList);
            }
            int nodes = 0;
            for (Flows.Col c : f.columns()) {
                nodes += c.nodes().size();
            }
            List<Object> tables = new ArrayList<>();
            for (Map<String, Object> t : f.tables()) {
                tables.add(D.with(t, "words", D.t(t, "crud") ? Flows.crudWords(D.str(t, "crud")) : "uses"));
            }
            List<Object> comments = new ArrayList<>();
            for (Map<String, Object> c : batchComments.getOrDefault(f.id(), List.of())) {
                comments.add(D.with(c, "link", link(c.get("file"), null)));
            }
            return D.of("flow", f, "diagram", D.of("title", f.kind().equals("screen") ? "Flow" : "Batch flow", "source", f.mermaid(),
                    "nodes", (long) nodes, "note", f.note(), "legend", true), "steps", steps, "tables", tables,
                    "link", link(f.evidence()), "comments", comments, "story", story,
                    "component_slug", f.component() != null && !f.component().isEmpty() ? RunData.slug(f.component()) : "");
        }

        void flows() {
            List<Flows.Flow> all = new ArrayList<>(screenFlows);
            all.addAll(batchFlows);
            for (Flows.Flow fl : all) {
                Map<String, Object> ctx = flowCtx(fl, null);
                ctx.put("audience", "technical");
                ctx.put("diagrams", true);
                render("flow.html", "flows/" + fl.slug() + ".html", "flows",
                        (fl.kind().equals("screen") ? "Screen" : "Batch") + " flow: " + fl.name(), ctx);
            }
            render("flows.html", "flows.html", "flows", "Screen and batch flows", D.of("audience", "technical",
                    "screen_flows", screenFlows, "batch_flows", batchFlows, "said", batchSaid, "week", Flows.weekGrid(batchFlows),
                    "days", Flows.DAY_NAMES));
        }

        void dataModel() {
            Map<String, Object> mx = Flows.crudMatrix(rd);
            List<Values.Tuple> top = summaryTopTables();
            top.sort((a, b) -> Long.compare((Long) b.get(1), (Long) a.get(1)));
            top = top.subList(0, Math.min(15, top.size()));
            List<Object> labels = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            top.forEach(t -> {
                labels.add(t.get(0));
                values.add(t.get(1));
            });
            Map<String, Object> refsChart = new Visuals.Chart("table-refs", "Most used tables", "bar", labels).values(values)
                    .colors("series-6").label("SQL statements").category("Table").horizontal(true).build();
            List<Flows.Pair> pairs = Flows.tablesUsedTogether(rd);
            render("data-model.html", "data-model.html", "data-model", "Data model", D.of("matrix", mx, "refs_chart", refsChart,
                    "data_map", D.of("title", "Tables used together in the same SQL statement", "source", Flows.dataMapMermaid(rd),
                            "nodes", (long) pairs.size(), "note",
                            "A line joins two tables read or written by the same statement (joins, "
                                    + "sub-queries): a hint of the relationships, not the declared keys."),
                    "charts", true, "diagrams", true));
        }

        List<Values.Tuple> summaryTopTables() {
            Map<String, Long> res = new LinkedHashMap<>();
            for (Map<String, Object> st : rd.items("sql_statements")) {
                Set<String> set = new LinkedHashSet<>();
                D.strings(st, "tables").forEach(t -> set.add(t.toUpperCase(java.util.Locale.ROOT)));
                set.forEach(t -> res.merge(t, 1L, Long::sum));
            }
            List<Values.Tuple> outList = new ArrayList<>();
            res.forEach((k, v) -> outList.add(RunData.tuple(k, v)));
            return outList;
        }

        void catalogue(String pageId, List<Catalogues.Catalogue> list) {
            String title = pageId.equals("batch-jobs") ? "Batch jobs" : list.get(0).title();
            List<Object> names = new ArrayList<>();
            list.forEach(c -> names.add(c.name()));
            render("catalogue.html", pageId + ".html", pageId, title, D.of("catalogues", list, "data_scripts", names,
                    "batch_csv", pageId.equals("batch-jobs"), "csv_file", pageId.equals("web-services") ? "web-services.csv" : null));
        }

        /** {@code Counter.most_common()}: by count, ties in first-seen order. */
        static List<Map.Entry<String, Long>> mostCommon(Map<String, Long> counter) {
            List<Map.Entry<String, Long>> l = new ArrayList<>(counter.entrySet());
            l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            return l;
        }

        void postgresql() {
            Map<String, Object> grades = D.map(counts, "pg_grades");
            List<Object> gv = new ArrayList<>();
            List<Object> gc = new ArrayList<>();
            for (String g : GRADES) {
                gv.add(D.get(grades, g, 0L));
                gc.add(GRADE_COLOR.get(g));
            }
            Map<String, Object> gradeChart = bar("grades", "SQL statements by PostgreSQL grade", GRADES, gv, gc, "Statements", "Grade",
                    false, "");
            Map<String, Long> constructs = new LinkedHashMap<>();
            for (Map<String, Object> st : rd.items("sql_statements")) {
                for (String v : D.strings(st, "vendor_features")) {
                    constructs.merge(v, 1L, Long::sum);
                }
            }
            List<Map.Entry<String, Long>> top = mostCommon(constructs);
            top = top.subList(0, Math.min(15, top.size()));
            List<Object> labels = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            top.forEach(en -> {
                labels.add(en.getKey());
                values.add(en.getValue());
            });
            Map<String, Object> constructChart = bar("constructs", "Vendor constructs (statements using each)", labels, values,
                    "series-1", "Statements", "Construct", true, "Top 15; one statement can use several.");
            List<Object> procedures = new ArrayList<>();
            for (Map<String, Object> o : rd.items("db_objects")) {
                if (List.of("package", "procedure", "function", "trigger").contains(D.s(o, "object_type"))) {
                    procedures.add(D.of("name", o.get("name"), "type", o.get("object_type"), "grade", D.s(o, "pg_grade"),
                            "reason", D.s(o, "reason"), "link", link(rd.firstEvidence(o))));
                }
            }
            render("postgresql.html", "postgresql.html", "postgresql", "PostgreSQL readiness", D.of("grade_chart", gradeChart,
                    "construct_chart", constructChart, "procedures", procedures, "data_scripts", new ArrayList<>(List.of("sql")),
                    "charts", true));
        }

        void security() {
            Map<String, Object> vl = D.map(counts, "vulnerable_libraries");
            Map<String, Object> bySev = D.map(vl, "by_severity");
            List<String> sevs = new ArrayList<>();
            for (String s : SEVERITIES) {
                if (bySev.containsKey(s)) {
                    sevs.add(s);
                }
            }
            if (sevs.isEmpty()) {
                sevs = SEVERITIES.subList(0, 4);
            }
            List<Object> sv = new ArrayList<>();
            List<Object> sc = new ArrayList<>();
            for (String s : sevs) {
                sv.add(D.get(bySev, s, 0L));
                sc.add(SEVERITY_COLOR.get(s));
            }
            Map<String, Object> severityChart = bar("vuln-sev", "Vulnerable libraries by highest severity", sevs, sv, sc, "Libraries",
                    "Severity", false, "");
            List<Map.Entry<String, Object>> recs = new ArrayList<>(D.map(counts, "recommendations").entrySet());
            recs.sort((a, b) -> Double.compare(D.num(b.getValue()), D.num(a.getValue())));
            List<Object> rl = new ArrayList<>();
            List<Object> rv = new ArrayList<>();
            recs.forEach(en -> {
                rl.add(en.getKey());
                rv.add(en.getValue());
            });
            Map<String, Object> actionChart = bar("actions", "Libraries by recommended action", rl, rv, "series-1", "Libraries", "Action",
                    true, "");
            List<Object> points = new ArrayList<>();
            List<Object> pointNames = new ArrayList<>();
            for (Map<String, Object> lib : rd.items("libraries")) {
                if (!D.t(lib, "cves")) {
                    continue;
                }
                Object cvss = 0L;
                for (Map<String, Object> v : D.maps(lib, "vulnerabilities")) {
                    Object c = D.or(D.get(v, "cvss"), 0L);
                    if (Values.compare(c, cvss) > 0) {
                        cvss = c;
                    }
                }
                long used = D.list(lib, "where_used").size();
                String name = TextUtil.strip(Catalogues.libraryName(lib) + " " + D.s(lib, "version"));
                long n = D.list(lib, "cves").size();
                pointNames.add(name);
                points.add(D.of("x", n, "y", cvss, "r", Math.min(22, 5 + 2 * used), "name", name,
                        "label", name + ": " + n + " CVE(s), highest CVSS " + Py.str(cvss) + ", used in " + used + " place(s)"));
            }
            Map<String, Object> bubbleChart = new Visuals.Chart("vuln-bubble", "Vulnerable libraries: how many CVEs and how severe",
                    "bubble", pointNames).extra("points", points).extra("xLabel", "Known vulnerabilities (CVEs)")
                    .extra("yLabel", "Highest CVSS score").category("Library").extra("color", "series-8")
                    .note("One bubble per vulnerable library; a bigger bubble is used in more places.").build();
            List<Map<String, Object>> libs = new ArrayList<>();
            for (Map<String, Object> lib : rd.items("libraries")) {
                if (D.t(lib, "cves") || D.t(lib, "eol")) {
                    libs.add(lib);
                }
            }
            libs.sort((a, b) -> {
                int ra = SEVERITIES.indexOf(D.s(a, "max_severity"));
                int rb = SEVERITIES.indexOf(D.s(b, "max_severity"));
                int c = Integer.compare(ra < 0 ? 9 : ra, rb < 0 ? 9 : rb);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(D.list(b, "cves").size(), D.list(a, "cves").size());
                return c != 0 ? c : PyJson.compareCodePoints(Catalogues.libraryName(a), Catalogues.libraryName(b));
            });
            List<Object> risky = new ArrayList<>();
            for (Map<String, Object> lib : libs.subList(0, Math.min(20, libs.size()))) {
                risky.add(D.of("name", Catalogues.libraryName(lib), "version", D.s(lib, "version"), "severity", D.s(lib, "max_severity"),
                        "cves", (long) D.list(lib, "cves").size(),
                        "eol", D.t(lib, "eol_date") ? D.str(lib, "eol_date") : D.t(lib, "eol") ? "yes" : "",
                        "action", D.get(D.map(lib, "recommendation"), "action", ""), "target", Catalogues.recommended(lib),
                        "link", link(rd.firstEvidence(lib))));
            }
            List<Object> flags = new ArrayList<>();
            long unknown = 0;
            for (Map<String, Object> lib : rd.items("libraries")) {
                String lic = D.s(lib, "license");
                if (lic.isEmpty()) {
                    unknown++;
                } else if (COPYLEFT.matcher(lic).find()) {
                    flags.add(D.of("name", Catalogues.libraryName(lib), "version", D.s(lib, "version"), "licence", lic,
                            "why", "copyleft: check the distribution terms"));
                }
            }
            render("security.html", "security.html", "security", "Dependency security", D.of(
                    "vuln_sources", D.get(rd.run, "vulnerability_sources", new ArrayList<>()), "severity_chart", severityChart,
                    "action_chart", actionChart, "bubble_chart", bubbleChart, "risky", risky, "licence_flags", flags,
                    "licence_unknown", unknown, "library_count", (long) rd.items("libraries").size(), "has_sbom", rd.hasSbom,
                    "data_scripts", new ArrayList<>(List.of("libraries")), "charts", true));
        }

        /** The Copilot enrichment, validated by {@code legacy-audit validate}, labelled as such. */
        void analysis() {
            Map<String, Object> status = Status.compute(rd.runDir);
            java.util.function.Function<Map<String, Object>, Map<String, Object>> cite = item -> {
                List<Object> links = new ArrayList<>();
                for (Map<String, Object> ev : D.maps(item, "evidence")) {
                    links.add(link(D.get(ev, "file"), D.get(ev, "line")));
                }
                return D.with(item, "links", links);
            };
            List<Object> stages = new ArrayList<>();
            for (Stages.Stage st : Status.activeStages(rd.runDir)) {
                Map<String, Object> entry = D.map(D.map(status, "stages"), st.name());
                String state = !D.t(entry, "present") ? "missing" : D.t(entry, "stale") ? "stale"
                        : D.t(entry, "validated") ? "valid" : "not validated";
                stages.add(D.of("number", (long) st.number(), "name", st.name(), "title", st.title(), "state", state,
                        "changed", D.get(entry, "changed", new ArrayList<>())));
            }
            java.util.function.Function<String, List<Object>> diagramList = stage -> {
                List<Object> res = new ArrayList<>();
                for (Map<String, Object> d : D.maps(D.map(e, stage), "diagrams")) {
                    res.add(D.of("title", d.get("title"), "source", d.get("mermaid"), "nodes", 1L,
                            "note", "Drawn by the Copilot agent (enrichment), not by the scanner."));
                }
                return res;
            };
            List<Map<String, Object>> risks = new ArrayList<>();
            for (Map<String, Object> r : D.maps(D.map(e, "risks"), "risks")) {
                risks.add(cite.apply(r));
            }
            risks.sort((a, b) -> {
                int ia = SEVERITIES.indexOf(Py.str(a.get("severity")));
                int ib = SEVERITIES.indexOf(Py.str(b.get("severity")));
                return Integer.compare(ia < 0 ? 9 : ia, ib < 0 ? 9 : ib);
            });
            List<Object> keyPoints = new ArrayList<>();
            D.maps(D.map(e, "overview"), "key_points").forEach(k -> keyPoints.add(cite.apply(k)));
            List<Object> classes = new ArrayList<>();
            D.maps(D.map(e, "classification"), "components").forEach(c -> classes.add(cite.apply(c)));
            List<Object> flowList = new ArrayList<>();
            D.maps(D.map(e, "flows"), "flows").forEach(fl -> flowList.add(cite.apply(fl)));
            List<Object> mappings = new ArrayList<>();
            D.maps(D.map(e, "service_mapping"), "mappings").forEach(m -> mappings.add(D.with(cite.apply(m), "service",
                    rd.label(D.str(m, "service_id")))));
            Runtime.Callable label = (args, kw) -> rd.label(Values.str(args.get(0)));
            render("analysis.html", "analysis.html", "analysis", "Analysis", D.of("stages", stages, "e", e, "key_points", keyPoints,
                    "classes", classes, "flows", flowList, "mappings", mappings, "risks", risks,
                    "risk_matrix", risks.isEmpty() ? null : Visuals.riskMatrix(risks),
                    "gantt", D.t(e, "plan") ? Visuals.gantt(D.maps(D.map(e, "plan"), "waves")) : null,
                    "arch_diagrams", diagramList.apply("architecture"), "target_diagrams", diagramList.apply("target_architecture"),
                    "label", label, "diagrams", true));
        }

        void findings() {
            Map<String, Object> bySev = D.map(counts, "findings_by_severity");
            List<String> sevs = new ArrayList<>();
            List<Object> sv = new ArrayList<>();
            List<Object> sc = new ArrayList<>();
            for (String s : SEVERITIES) {
                if (bySev.containsKey(s)) {
                    sevs.add(s);
                    sv.add(bySev.get(s));
                    sc.add(SEVERITY_COLOR.get(s));
                }
            }
            Map<String, Object> severityChart = bar("find-sev", "Findings by severity", sevs, sv, sc, "Findings", "Severity", false, "");
            Map<String, Long> byCat = new LinkedHashMap<>();
            for (Map<String, Object> fd : rd.items("findings")) {
                byCat.merge(D.t(fd, "category") ? D.str(fd, "category") : "other", 1L, Long::sum);
            }
            List<Object> cl = new ArrayList<>();
            List<Object> cv = new ArrayList<>();
            mostCommon(byCat).forEach(en -> {
                cl.add(en.getKey());
                cv.add(en.getValue());
            });
            Map<String, Object> categoryChart = bar("find-cat", "Findings by category", cl, cv, "series-1", "Findings", "Category", true, "");
            List<Map.Entry<String, Object>> rules = new ArrayList<>(D.map(counts, "findings").entrySet());
            rules.sort((a, b) -> {
                int c = Double.compare(D.num(b.getValue()), D.num(a.getValue()));
                return c != 0 ? c : PyJson.compareCodePoints(a.getKey(), b.getKey());
            });
            List<Object> rl = new ArrayList<>();
            List<Object> rv = new ArrayList<>();
            rules.forEach(en -> {
                rl.add(en.getKey());
                rv.add(en.getValue());
            });
            Map<String, Object> ruleChart = bar("find-rule", "Findings by rule", rl, rv, "series-1", "Findings", "Rule", true, "");
            render("findings.html", "findings.html", "findings", "Findings", D.of("severity_chart", severityChart,
                    "category_chart", categoryChart, "rule_chart", ruleChart, "data_scripts", new ArrayList<>(List.of("findings")),
                    "charts", true));
        }

        void appendix() {
            Map<String, Object> run = rd.run;
            Path configPath = D.t(run, "config_path") ? Path.of(D.str(run, "config_path")) : null;
            String text;
            if (configPath != null && Files.isRegularFile(configPath)) {
                try {
                    text = TextUtil.sub(SECRET_LINE, Fmt.readText(configPath), m -> m.group(1) + "****");
                } catch (IOException ex) {
                    text = "(configuration file not found)";
                }
            } else {
                text = "(configuration file not found)";
            }
            Map<String, Object> levels = new LinkedHashMap<>();
            List<String> notable = new ArrayList<>();
            Path log = rd.runDir.resolve("logs").resolve("scan.log");
            if (Files.exists(log)) {
                try {
                    for (String line : TextUtil.pySplitlines(Fmt.readText(log))) {
                        Matcher m = LOG_LINE.matcher(line);
                        if (m.lookingAt()) {
                            levels.merge(m.group(1), 1L, (a, b) -> (Long) a + (Long) b);
                            if (List.of("WARNING", "ERROR", "CRITICAL").contains(m.group(1))) {
                                notable.add(line);
                            }
                        }
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            List<Values.Tuple> analyzers = new ArrayList<>();
            new TreeMap<String, Object>(PyJson::compareCodePoints) {
                {
                    putAll(D.map(run, "analyzers"));
                }
            }.forEach((k, v) -> analyzers.add(RunData.tuple(k, v)));
            Map<String, Object> runInfo = new LinkedHashMap<>();
            for (String k : List.of("run_id", "started_at", "finished_at", "files_total", "files_analyzed", "files_cached",
                    "full_scan", "unresolved_references", "config_hash")) {
                runInfo.put(k, run.get(k));
            }
            runInfo.put("timings", D.get(run, "timings", new LinkedHashMap<>()));
            runInfo.put("warnings", D.get(run, "warnings", new ArrayList<>()));
            render("appendix.html", "appendix.html", "appendix", "Appendix", D.of("counting_rules", D.get(run, "counting_rules",
                    new LinkedHashMap<>()), "input_coverage", D.get(run, "coverage", new ArrayList<>()), "analyzers", analyzers,
                    "run", runInfo, "config_name", configPath != null ? configPath.getFileName().toString() : "audit.yaml",
                    "config_text", text, "log_levels", levels,
                    "log_lines", notable.subList(Math.max(0, notable.size() - 50), notable.size()), "glossary", GLOSSARY));
        }
    }
}
