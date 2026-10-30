package com.legacyaudit.reports;

import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.model.json.PyNum;
import com.legacyaudit.reports.jinja.Environment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * {@code legacy-audit report --type mgmt}: the management deck as a static HTML slider.
 *
 * <p>{@code deck.json} holds the slides as data (executive summary, functional view, current
 * architecture, technical deep-dive, target architecture, plan, risks and next steps). Numbers
 * come from the run's facts; interpretation comes from the enrichment. A slide part whose
 * enrichment stage is missing shows a placeholder naming the prompt to run, so the deck can be
 * built straight after a scan. The page opens from disk; Chart.js and Mermaid are the vendored
 * copies of the technical report. There is no PowerPoint copy (decision 223).
 *
 * <p>Block types: {@code kpis}, {@code bullets}, {@code text}, {@code chart} (bar, doughnut,
 * line; one series), {@code table}, {@code diagram} (Mermaid source), {@code gauges},
 * {@code heatmap}, {@code capmap}, {@code layers}, {@code flow}, {@code gantt} and
 * {@code placeholder}.
 */
public final class MgmtReport {

    private MgmtReport() {}

    public static final String DECK_VERSION = "1";
    static final List<String> SEVERITIES = List.of("critical", "high", "medium", "low", "info");
    static final Map<String, String> SEVERITY_TOKEN = Map.of(
            "critical", "critical", "high", "serious", "medium", "warning", "low", "info", "info", "info");
    static final List<String> GRADES = List.of("auto", "review", "manual");
    static final Map<String, String> GRADE_TOKEN = Map.of("auto", "good", "review", "warning", "manual", "critical");
    static final Map<String, String> SECTION_COLOR = Map.of(
            "exec", "series-1", "functional", "series-7", "current", "series-3", "technical", "series-2",
            "target", "series-6", "plan", "series-4", "risks", "series-8");
    /** Screen flows and batch flows given their own slide. */
    static final int KEY_FLOWS = 3;
    /** Rows per table on a slide. */
    static final int MAX_ROWS = 10;
    /** Characters per table cell on a slide (full text in the technical report). */
    static final int MAX_CELL = 100;
    static final List<String> RESOLVING_ACTIONS = List.of("upgrade", "replace", "remove");

    record Section(String key, String title, String audience) {}

    static final List<Section> SECTIONS = List.of(
            new Section("exec", "Executive summary", "Top management"),
            new Section("functional", "Functional view", "Business and functional teams"),
            new Section("current", "Current architecture", "Architects"),
            new Section("technical", "Technical deep-dive", "Tech leads and developers"),
            new Section("target", "Target architecture", "Architects"),
            new Section("plan", "Plan", "All"),
            new Section("risks", "Risks, assumptions and next steps", "All"));

    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{3,8}$");
    private static final Pattern FONT_BAD = Pattern.compile("[;{}<>\\\\]");
    private static final List<String> MGMT_ASSETS = List.of("deck.css", "deck.js");
    private static final List<String> VENDOR = List.of(
            "chart-4.4.7.umd.js", "LICENSE-chartjs.md", "mermaid-11.4.1.min.js", "LICENSE-mermaid.txt");

    /** The {@code branding} section of audit.yaml with its defaults. */
    public static Map<String, Object> defaultBranding() {
        return D.of("theme", "neutral", "logo", null, "primary_color", "#1f4e79", "accent_color", "#2e75b6",
                "font", "Segoe UI, Arial, sans-serif", "pptx_template", null);
    }

    /** A number as the deck prints it: grouped, at most one decimal, no trailing zero. */
    public static String num(Object v) {
        if (v instanceof Double || v instanceof Float) {
            String s = Fmt.fixed(((Number) v).doubleValue(), 1, true);
            return TextUtil.rstrip(TextUtil.rstrip(s, "0"), ".");
        }
        if (v instanceof Boolean b) {
            return b ? "1" : "0";
        }
        if (v instanceof Number n) {
            return String.format(Locale.ROOT, "%,d", n.longValue());
        }
        return Py.str(v);
    }

    static String cut(String text) {
        if (text.codePointCount(0, text.length()) <= MAX_CELL) {
            return text;
        }
        return TextUtil.rstrip(text.substring(0, text.offsetByCodePoints(0, MAX_CELL - 1))) + "…";
    }

    /** Build {@code reports/mgmt/} (index.html and deck.json) for a run; returns the page. */
    public static Path build(Path runDir, Map<String, Object> branding, Path configDir) {
        RunData rd = RunData.load(runDir);
        Path out = runDir.resolve("reports").resolve("mgmt");
        buildInto(rd, out, branding, configDir);
        Path latest = Status.latestCopy(runDir);
        if (latest != null) {
            Path dest = latest.resolve("reports").resolve("mgmt");
            Fmt.rmtree(dest);
            SummaryReport.copyTree(out, dest);
        }
        return out.resolve("index.html");
    }

    /** The deck of a run into a folder: deck.json, index.html and assets. */
    public static void buildInto(RunData rd, Path out, Map<String, Object> branding, Path configDir) {
        Map<String, Object> b = branding == null ? defaultBranding() : branding;
        Map<String, Object> deck = deck(rd, b);
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("deck.json"), PyJson.pretty(deck), StandardCharsets.UTF_8);
            Path assets = out.resolve("assets");
            Fmt.rmtree(assets);
            Fmt.copyResources("com/legacyaudit/reports/mgmt_assets", MGMT_ASSETS, assets);
            Fmt.copyResources("com/legacyaudit/reports/assets/vendor", VENDOR, assets.resolve("vendor"));
            String logo = null;
            String logoSetting = D.sOrNull(b, "logo");
            if (logoSetting != null) {
                Path src = Path.of(logoSetting);
                if (!src.isAbsolute() && configDir != null) {
                    src = configDir.resolve(src);
                }
                String name = src.getFileName() == null ? "" : src.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String suffix = dot <= 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(src) && List.of(".png", ".jpg", ".jpeg", ".svg", ".gif").contains(suffix)) {
                    Files.copy(src, assets.resolve("logo" + suffix));
                    logo = "assets/logo" + suffix;
                }
            }
            String primary = D.s(b, "primary_color");
            String color = COLOR.matcher(primary).matches() ? primary : "#1f4e79";
            String font = FONT_BAD.matcher(D.s(b, "font")).replaceAll("");
            Environment env = new Environment("com/legacyaudit/reports/mgmt_html").global("fmt", args -> num(args.get(0)));
            String html = env.render("deck.html", D.of("deck", deck, "logo", logo, "brand_color", color, "brand_font", font));
            Files.writeString(out.resolve("index.html"), html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code deck.json} for a run. */
    public static Map<String, Object> deck(RunData rd, Map<String, Object> branding) {
        Map<String, Object> enrichment = Status.loadEnrichment(rd.runDir);
        Map<String, Object> status = Status.compute(rd.runDir);
        Deck deck = new Deck(rd, enrichment, status);
        deck.executive();
        deck.functional();
        deck.current();
        deck.technical();
        if (!rd.auditOnly()) { // no target: no target architecture or plan slides
            deck.target();
            deck.plan();
        }
        deck.risks();
        List<String> stale = D.strings(status, "stale");
        Map<String, Object> used = new LinkedHashMap<>();
        for (String name : enrichment.keySet()) {
            used.put(name, stale.contains(name) ? "stale" : "present");
        }
        List<Object> sections = new ArrayList<>();
        for (Section s : SECTIONS) {
            if (!(rd.auditOnly() && (s.key().equals("target") || s.key().equals("plan")))) {
                sections.add(D.of("key", s.key(), "title", s.title(), "audience", s.audience()));
            }
        }
        return D.of(
                "version", DECK_VERSION,
                "application", D.get(rd.run, "application", ""),
                "run_id", D.get(rd.run, "run_id", rd.runDir.getFileName().toString()),
                "generated_from", D.of(
                        "facts", "data/audit-summary.json, data/effort.json, data/*.json",
                        "enrichment", used,
                        "missing", D.get(status, "missing")),
                "branding", branding,
                "palette", Palette.PALETTE,
                "sections", sections,
                "target", rd.target,
                "slides", deck.slides);
    }

    static List<Object> row(Object... cells) {
        return java.util.Arrays.asList(cells);
    }

    private static int severityOrder(Object severity) {
        int i = SEVERITIES.indexOf(Py.str(severity));
        return i < 0 ? 9 : i;
    }

    private static List<String> sortedStrings(Collection<String> items) {
        List<String> out = new ArrayList<>(items);
        out.sort(PyJson::compareCodePoints);
        return out;
    }

    /** Keys of a count map, largest first, then by name. */
    private static List<String> byCount(Map<String, ?> counts) {
        List<String> out = new ArrayList<>(counts.keySet());
        out.sort((a, b) -> {
            int c = Double.compare(D.num(counts.get(b)), D.num(counts.get(a)));
            return c != 0 ? c : PyJson.compareCodePoints(a, b);
        });
        return out;
    }

    private static List<Object> values(Map<String, ?> counts, List<String> keys) {
        List<Object> out = new ArrayList<>();
        keys.forEach(k -> out.add(counts.get(k)));
        return out;
    }

    static final class Deck {
        final RunData rd;
        final Map<String, Object> s;
        final Map<String, Object> e;
        final Map<String, Object> status;
        final List<Map<String, Object>> slides = new ArrayList<>();
        Section section = SECTIONS.get(0);

        Deck(RunData rd, Map<String, Object> enrichment, Map<String, Object> status) {
            this.rd = rd;
            this.s = rd.summary;
            this.e = enrichment;
            this.status = status;
        }

        // ---- helpers ---------------------------------------------------------------------------

        void start(String key) {
            section = SECTIONS.stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
            if (!key.equals("exec")) {
                slides.add(D.of("id", "section-" + key, "section", section.title(), "audience", section.audience(),
                        "type", "section", "title", section.title(), "subtitle", "For " + section.audience().toLowerCase(Locale.ROOT),
                        "color", SECTION_COLOR.get(key), "blocks", List.of(), "notes", "", "stale", List.of()));
            }
        }

        void addUnless(boolean skip, String sid, String title, List<Map<String, Object>> blocks, String notes) {
            if (!skip) {
                add(sid, title, blocks, notes);
            }
        }

        void add(String sid, String title, List<Map<String, Object>> blocks) {
            add(sid, title, blocks, "");
        }

        void add(String sid, String title, List<Map<String, Object>> blocks, String notes) {
            add(sid, title, blocks, notes, "content", "", "");
        }

        void add(String sid, String title, List<Map<String, Object>> blocks, String notes, String kind, String subtitle,
                String layout) {
            TreeSet<String> uses = new TreeSet<>(PyJson::compareCodePoints);
            for (Map<String, Object> b : blocks) {
                if (D.t(b, "stage")) {
                    uses.add(D.str(b, "stage"));
                }
            }
            List<String> staleNow = D.strings(status, "stale");
            List<Object> stale = new ArrayList<>();
            uses.stream().filter(staleNow::contains).forEach(stale::add);
            slides.add(D.of("id", sid, "section", section.title(), "audience", section.audience(), "type", kind,
                    "title", title, "subtitle", subtitle, "blocks", new ArrayList<Object>(blocks), "notes", notes,
                    "stale", stale, "color", SECTION_COLOR.get(section.key()), "layout", layout));
        }

        Map<String, Object> stage(String name) {
            Object v = e.get(name);
            return v instanceof Map<?, ?> ? D.asMap(v) : null;
        }

        Map<String, Object> placeholder(String stage, String what) {
            Stages.Stage st = Stages.BY_NAME.get(stage);
            return D.of("type", "placeholder", "stage", stage, "text", what + ": run /audit-enrich " + stage
                    + " in Copilot (stage " + st.number() + ", " + st.title() + "), then rebuild the deck.");
        }

        Map<String, Object> kpi(String label, String metric) {
            return kpi(label, metric, "");
        }

        Map<String, Object> kpi(String label, String metric, String unit) {
            Map<String, Object> src = D.with(s, "effort", rd.effort == null ? new LinkedHashMap<>() : rd.effort);
            return D.of("label", label, "value", Fmt.at(src, metric), "metric", metric, "unit", unit);
        }

        static Map<String, Object> chart(String chart, String title, List<String> labels, List<Object> values, String unit) {
            return chart(chart, title, labels, values, unit, null);
        }

        static Map<String, Object> chart(String chart, String title, List<String> labels, List<Object> values, String unit,
                List<String> colors) {
            return D.of("type", "chart", "chart", chart, "title", title, "labels", labels, "values", values, "unit", unit,
                    "colors", colors != null && !colors.isEmpty() ? colors : java.util.Collections.nCopies(labels.size(), "accent"));
        }

        /** A table; beyond MAX_ROWS the rest is counted in {@code more}. */
        static Map<String, Object> table(List<String> columns, List<List<Object>> rows) {
            return table(columns, rows, "");
        }

        static Map<String, Object> table(List<String> columns, List<List<Object>> rows, String title) {
            List<Object> cells = new ArrayList<>();
            for (List<Object> r : rows) {
                List<Object> row = new ArrayList<>();
                for (Object c : r) {
                    row.add(c instanceof Number || c instanceof Boolean ? num(c) : cut(Py.truthy(c) ? Py.str(c) : ""));
                }
                cells.add(row);
            }
            return D.of("type", "table", "title", title, "columns", columns,
                    "rows", new ArrayList<>(cells.subList(0, Math.min(MAX_ROWS, cells.size()))),
                    "more", (long) Math.max(0, cells.size() - MAX_ROWS));
        }

        static Map<String, Object> staged(Map<String, Object> block, String stage) {
            block.put("stage", stage);
            return block;
        }

        String narrative(String who) {
            Object n = D.get(stage("narratives"), who);
            return Py.truthy(n) ? Py.str(D.get(n, "text", "")) : "";
        }

        void flowSlide(Flows.Flow f, Map<String, Object> story, String what) {
            Map<String, Object> block = f.deckBlock();
            Map<String, Object> words;
            if (story != null) {
                List<Object> items = new ArrayList<>();
                items.add(story.get("purpose"));
                if (f.kind().equals("screen")) {
                    List<Object> acts = D.list(story, "user_actions");
                    items.addAll(acts.subList(0, Math.min(4, acts.size())));
                } else {
                    if (D.t(story, "when")) {
                        items.add("When: " + D.str(story, "when"));
                    }
                    List<Object> st = new ArrayList<>();
                    D.maps(story, "steps").forEach(x -> st.add(x.get("description")));
                    items.addAll(st.subList(0, Math.min(4, st.size())));
                    if (!D.t(story, "steps")) {
                        List<Object> pg = new ArrayList<>();
                        D.maps(story, "programs").forEach(x -> pg.add(D.str(x, "name") + ": " + D.str(x, "what_it_does")));
                        items.addAll(pg.subList(0, Math.min(4, pg.size())));
                    }
                    if (D.t(story, "on_failure")) {
                        items.add("If it fails: " + D.str(story, "on_failure"));
                    }
                }
                words = D.of("type", "bullets", "stage", "functional", "items", items);
            } else {
                List<Object> items = new ArrayList<>();
                items.add(f.summary());
                for (Map<String, Object> c : Flows.codeComments(rd, f, 3)) {
                    items.add("The code says: " + D.last(D.str(c, "file"), "/") + ": " + String.join(" ", D.strings(c, "lines")));
                }
                words = D.of("type", "bullets", "items", items);
            }
            add("fn-" + f.kind() + "-" + f.slug(), what + " flow: " + f.name(), List.of(block, words),
                    "The flow is drawn from the scan (services, SQL, tables, scheduler definitions); "
                            + "the words beside it are the Copilot agent's, checked by legacy-audit validate.",
                    "content", "", "wide-left");
        }

        Map<String, Object> gauges() {
            Object c = D.get(s, "counts", new LinkedHashMap<>());
            long sql = Fmt.n(c, "sql.total");
            long libs = Fmt.n(c, "libraries.total");
            List<Map<String, Object>> cov = D.maps(rd.run, "coverage");
            long present = cov.stream().filter(x -> "present".equals(x.get("status"))).count();
            List<Object> items = new ArrayList<>();
            if (!rd.auditOnly()) { // audit only: no target database
                items.add(Visuals.gauge("SQL converting automatically", (Number) Fmt.at(c, "pg_grades.auto"), sql, "good",
                        num(Fmt.at(c, "pg_grades.auto")) + " of " + num(sql) + " statements"));
            }
            long vulnerable = Fmt.n(c, "vulnerable_libraries.total");
            long eol = Fmt.n(c, "eol_libraries");
            items.add(Visuals.gauge("Libraries with no known vulnerability", libs - vulnerable, libs, "series-1",
                    num(vulnerable) + " vulnerable"));
            items.add(Visuals.gauge("Libraries still supported", libs - eol, libs, "series-3", num(eol) + " end of life"));
            items.add(Visuals.gauge("Inputs provided", present, (long) cov.size(), "series-7",
                    present + " of " + cov.size() + " kinds"));
            return D.of("type", "gauges", "items", items);
        }

        /** Rows of {@code {label, cells: [{text, level}]}}; {@code scale} is seq (0..6) or risk (0..3). */
        static Map<String, Object> heatmap(String title, List<String> columns, List<Object> rows, String scale, String legend) {
            return D.of("type", "heatmap", "title", title, "columns", columns, "rows", rows, "scale", scale, "legend", legend);
        }

        Map<String, Object> crudHeatmap() {
            int limitTables = 8;
            int limitRows = 8;
            Map<String, Object> mx = Flows.crudMatrix(rd);
            List<String> allTables = D.strings(mx, "tables");
            List<String> tables = new ArrayList<>(allTables.subList(0, Math.min(limitTables, allTables.size())));
            Map<String, Object> cells = D.map(mx, "cells");
            long[] level = {0, 2, 3, 4, 6};
            List<Object> rows = new ArrayList<>();
            List<String> allRows = D.strings(mx, "rows");
            for (String r : allRows.subList(0, Math.min(limitRows, allRows.size()))) {
                List<Object> cs = new ArrayList<>();
                for (String t : tables) {
                    String text = Py.str(D.get(cells, r + "|" + t, ""));
                    cs.add(D.of("text", text, "level", level[text.length()]));
                }
                rows.add(D.of("label", r, "cells", cs));
            }
            return heatmap("Create, read, update, delete by component and table", tables, rows, "seq",
                    "C create · R read · U update · D delete; darker = more operations. "
                            + (allTables.size() > limitTables ? "Busiest " + limitTables + " of " + allTables.size() + " tables." : ""));
        }

        // ---- sections --------------------------------------------------------------------------

        void executive() {
            start("exec");
            Map<String, Object> ov = stage("overview");
            String app = D.t(rd.run, "application") ? D.str(rd.run, "application") : "Application";
            Map<String, Object> purpose = ov != null
                    ? D.of("type", "text", "text", ov.get("purpose"), "stage", "overview")
                    : placeholder("overview", "Purpose of the application");
            String started = Py.str(D.get(rd.run, "started_at", ""));
            add("exec-title", app + ": legacy audit", List.of(purpose), narrative("executive"), "title",
                    "Run " + Py.str(D.get(rd.run, "run_id", "")) + " · " + started.substring(0, Math.min(10, started.length()))
                            + " · Target: " + Py.str(D.get(rd.target, "label", "")), "");

            Map<String, Object> kpis = D.of("type", "kpis", "items", List.of(
                    kpi("Components", "totals.components"),
                    kpi("Services", "counts.services.total"),
                    kpi("Screens", "counts.screens.total"),
                    kpi("SQL statements", "counts.sql.total"),
                    kpi("Integrations", "counts.integrations.total"),
                    kpi("Batch jobs", "counts.batch_jobs.total"),
                    kpi("Libraries", "counts.libraries.total"),
                    kpi("Lines of code", "totals.loc_code")));
            Map<String, Object> points;
            if (ov != null) {
                List<Object> items = new ArrayList<>();
                D.maps(ov, "key_points").forEach(p -> items.add(p.get("text")));
                points = D.of("type", "bullets", "stage", "overview", "items", items);
            } else {
                points = placeholder("overview", "Key points");
            }
            add("exec-estate", "The estate at a glance", List.of(kpis, points),
                    "Counts are from the scan (audit-summary.json); counting rules are printed in "
                            + "the technical report's appendix.");
            add("exec-readiness", rd.auditOnly() ? "Health at a glance" : "Migration readiness", List.of(gauges()),
                    "Each ring is a share computed from the scan's counts (audit-summary.json).");

            Map<String, Object> risks = stage("risks");
            Map<String, Object> riskBlock;
            if (risks != null) {
                List<Map<String, Object>> top = sortedRisks(risks);
                List<List<Object>> rows = new ArrayList<>();
                for (Map<String, Object> r : top.subList(0, Math.min(5, top.size()))) {
                    rows.add(row(r.get("title"), r.get("severity"), r.get("mitigation"), D.get(r, "owner", "")));
                }
                riskBlock = staged(table(List.of("Risk", "Severity", "Mitigation", "Owner"), rows), "risks");
            } else {
                riskBlock = placeholder("risks", "Top 5 risks");
            }
            add("exec-risks", "Top 5 risks", List.of(riskBlock, severityChart("Scan findings by severity")));

            if (rd.auditOnly()) {
                return; // no target: no approach, timeline or effort
            }
            Map<String, Object> plan = stage("plan");
            Map<String, Object> effort = D.of("type", "kpis", "items", List.of(
                    kpi("Effort, low", "effort.low", "person-days"),
                    kpi("Effort, likely", "effort.likely", "person-days"),
                    kpi("Effort, high", "effort.high", "person-days")),
                    "note", Py.truthy(rd.effort) ? Py.str(Fmt.at(rd.effort, "label", "")) : "");
            if (!Py.truthy(rd.effort)) {
                effort = D.of("type", "text", "text", "No effort estimate in this run (effort.json).");
            }
            Map<String, Object> steps;
            if (plan != null) {
                long months = 0;
                List<Object> items = new ArrayList<>();
                items.add(plan.get("approach"));
                for (Map<String, Object> w : D.maps(plan, "waves")) {
                    long end = D.i(w, "start_month") + D.i(w, "duration_months");
                    months = Math.max(months, end);
                    items.add(D.str(w, "name") + ": " + D.str(w, "goal") + " (months " + D.i(w, "start_month") + " to " + end + ")");
                }
                if (months != 0) {
                    items.add("About " + months + " months in total");
                }
                steps = D.of("type", "bullets", "stage", "plan", "items", items);
            } else {
                steps = placeholder("plan", "Approach and timeline");
            }
            add("exec-approach", "Approach, timeline and effort", List.of(effort, steps),
                    "Effort is the scanner's estimate from configured weights, not a quote.");
        }

        List<Map<String, Object>> sortedRisks(Map<String, Object> risks) {
            List<Map<String, Object>> out = new ArrayList<>(D.maps(risks, "risks"));
            out.sort((a, b) -> Integer.compare(severityOrder(a.get("severity")), severityOrder(b.get("severity"))));
            return out;
        }

        Map<String, Object> severityChart(String title) {
            Map<String, Object> sev = D.asMap(D.or(Fmt.at(s, "counts.findings_by_severity", new LinkedHashMap<>()), new LinkedHashMap<>()));
            List<String> labels = SEVERITIES.stream().filter(x -> D.t(sev, x)).toList();
            return chart("bar", title, labels, values(sev, labels), "findings",
                    labels.stream().map(SEVERITY_TOKEN::get).toList());
        }

        Map<String, Object> counts(String path) {
            return D.asMap(D.or(Fmt.at(s, path, new LinkedHashMap<>()), new LinkedHashMap<>()));
        }

        void functional() {
            start("functional");
            Map<String, Object> cl = stage("classification");
            Map<String, Object> fn = stage("functional");
            Map<String, Object> block;
            if (fn != null && D.t(fn, "domains")) {
                List<Object> domains = new ArrayList<>();
                int i = 0;
                for (Map<String, Object> d : D.maps(fn, "domains")) {
                    List<Object> caps = new ArrayList<>();
                    D.maps(d, "capabilities").forEach(c -> caps.add(c.get("name")));
                    domains.add(D.of("name", d.get("name"), "color", "series-" + (i % 8 + 1), "caps", caps));
                    i++;
                }
                block = D.of("type", "capmap", "stage", "functional", "domains", domains);
            } else if (cl != null) {
                List<List<Object>> rows = new ArrayList<>();
                for (Map<String, Object> c : D.maps(cl, "components")) {
                    rows.add(row(c.get("component"), c.get("capability"), c.get("kind"), c.get("criticality")));
                }
                block = staged(table(List.of("Component", "Capability", "Kind", "Criticality"), rows), "classification");
            } else {
                block = placeholder("functional", "Capability map");
            }
            add("fn-capabilities", "Capability map", List.of(block));

            if (fn != null && D.t(fn, "use_cases")) {
                List<List<Object>> rows = new ArrayList<>();
                for (Map<String, Object> u : D.maps(fn, "use_cases")) {
                    rows.add(row(D.str(u, "id") + " " + D.str(u, "name"), u.get("actor"), u.get("goal")));
                }
                Map<String, Object> uc = staged(table(List.of("Use case", "Who", "Goal"), rows), "functional");
                List<Object> actorItems = new ArrayList<>();
                D.maps(fn, "actors").forEach(a -> actorItems.add(D.str(a, "name") + " (" + D.str(a, "kind") + ")"));
                Map<String, Object> actors = D.of("type", "bullets", "title", "Who uses it", "stage", "functional", "items", actorItems);
                add("fn-use-cases", "Users and use cases", List.of(actors, uc));
            } else {
                add("fn-use-cases", "Users and use cases", List.of(placeholder("functional", "Actors and use cases")));
            }

            Counter per = new Counter();
            for (Map<String, Object> sc : rd.items("screens")) {
                if (!D.t(sc, "partial")) {
                    per.add(D.t(sc, "component") ? D.str(sc, "component") : "(none)");
                }
            }
            List<String> names = byCount(per);
            Map<String, Object> screens = names.isEmpty()
                    ? D.of("type", "text", "text", "No screens found by the scan.")
                    : chart("bar", "Screens per component", names, values(per, names), "screens");
            add("fn-screens", "Screens per component", List.of(screens));

            Map<String, Object> fl = stage("flows");
            Map<String, Object> flowsBlock;
            if (fl != null) {
                List<Object> items = new ArrayList<>();
                D.maps(fl, "flows").forEach(f -> items.add(D.str(f, "name") + ": " + D.str(f, "summary")));
                flowsBlock = D.of("type", "bullets", "stage", "flows", "items", items);
            } else {
                flowsBlock = placeholder("flows", "Key business flows");
            }
            add("fn-flows", "Key business flows", List.of(flowsBlock));

            Map<String, Map<String, Object>> stories = new LinkedHashMap<>();
            D.maps(fn, "screens").forEach(x -> stories.put(D.str(x, "screen_id"), x));
            List<Flows.Flow> keyScreens = new ArrayList<>(Flows.screenFlows(rd));
            keyScreens.sort((a, b) -> {
                int c = Integer.compare(-a.services().size() - a.tables().size(), -b.services().size() - b.tables().size());
                return c != 0 ? c : PyJson.compareCodePoints(a.name(), b.name());
            });
            for (Flows.Flow f : keyScreens.subList(0, Math.min(KEY_FLOWS, keyScreens.size()))) {
                flowSlide(f, stories.get(f.id()), "Screen");
            }
            Map<String, Map<String, Object>> bstories = new LinkedHashMap<>();
            D.maps(fn, "batches").forEach(x -> bstories.put(D.str(x, "job_id"), x));
            List<Flows.Flow> batch = Flows.batchFlows(rd);
            List<Flows.Flow> keyBatch = new ArrayList<>(batch);
            keyBatch.sort((a, b) -> {
                int c = Integer.compare(-a.steps().size() - a.tables().size(), -b.steps().size() - b.tables().size());
                return c != 0 ? c : PyJson.compareCodePoints(a.name(), b.name());
            });
            for (Flows.Flow f : keyBatch.subList(0, Math.min(KEY_FLOWS, keyBatch.size()))) {
                flowSlide(f, bstories.get(f.id()), "Batch");
            }
            if (!batch.isEmpty()) {
                List<Map<String, Object>> week = Flows.weekGrid(batch);
                List<Object> rows = new ArrayList<>();
                for (Map<String, Object> r : week.subList(0, Math.min(MAX_ROWS, week.size()))) {
                    List<Object> cells = new ArrayList<>();
                    for (Object c : D.list(r, "cells")) {
                        cells.add(D.of("text", c, "level", Py.truthy(c) ? 4L : 0L));
                    }
                    rows.add(D.of("label", r.get("name"), "cells", cells));
                }
                List<Object> items = new ArrayList<>();
                week.forEach(r -> items.add(D.str(r, "name") + ": " + D.str(r, "text")));
                add("fn-batch-calendar", "Batch calendar", List.of(
                        heatmap("When each batch runs", Flows.DAY_NAMES, rows, "seq",
                                "Start times from the scheduler definitions (Autosys, cron)."),
                        D.of("type", "bullets", "items", items)));
            }
            if (fn != null && D.t(fn, "business_rules")) {
                List<List<Object>> rows = new ArrayList<>();
                for (Map<String, Object> r : D.maps(fn, "business_rules")) {
                    rows.add(row(D.str(r, "id") + " " + D.str(r, "name"), r.get("kind"), r.get("description")));
                }
                add("fn-rules", "Business rules to keep", List.of(staged(table(List.of("Rule", "Kind", "What it says"), rows), "functional")));
            } else {
                add("fn-rules", "Business rules to keep", List.of(placeholder("functional", "Business rules")));
            }

            Object n = D.get(stage("narratives"), "functional");
            Map<String, Object> users;
            if (Py.truthy(n)) {
                List<Object> items = new ArrayList<>();
                items.add(D.get(n, "text"));
                items.addAll(D.list(n, "key_messages"));
                users = D.of("type", "bullets", "stage", "narratives", "items", items);
            } else {
                users = placeholder("narratives", "What changes for users");
            }
            add("fn-users", "What changes for users", List.of(users));
        }

        void current() {
            start("current");
            List<List<Object>> stack = new ArrayList<>();
            for (Map<String, Object> t : D.maps(s, "tech_stack")) {
                stack.add(row(t.get("name"), D.or(t.get("version"), ""), D.or(t.get("category"), ""), D.or(t.get("eol"), "")));
            }
            add("cur-stack", "Technology stack", List.of(table(List.of("Technology", "Version", "Category", "End of life"), stack)));
            List<Map<String, Object>> servers = Servers.serverRows(rd, e);
            if (!servers.isEmpty()) {
                List<List<Object>> rows = new ArrayList<>();
                for (Map<String, Object> r : servers) {
                    rows.add(java.util.Arrays.asList(r.get("name"), r.get("kind"), r.get("version"), r.get("eol"),
                            String.join(", ", D.strings(r, "components")), r.get("target")));
                }
                add("cur-servers", "Application and web servers",
                        List.of(table(List.of("Product", "Kind", "Version", "End of life", "Modules", "Target"), rows)),
                        "From deployment descriptors, server configuration, Dockerfiles and start scripts.");
            }

            Map<String, Object> arch = stage("architecture");
            Map<String, Object> diag;
            if (arch != null && D.t(arch, "diagrams")) {
                Map<String, Object> d = D.maps(arch, "diagrams").get(0);
                diag = D.of("type", "diagram", "title", d.get("title"), "mermaid", d.get("mermaid"), "stage", "architecture");
            } else {
                Diagrams.Diagram g = Diagrams.componentGraph(rd);
                diag = D.of("type", "diagram", "title", "Components and their dependencies (from scan)", "mermaid", g.source(),
                        "note", g.note());
            }
            List<String> notes = new ArrayList<>();
            D.maps(arch, "notes").forEach(x -> notes.add(D.str(x, "text")));
            add("cur-components", "Component diagram", List.of(diag), String.join("; ", notes));
            if (arch != null && D.t(arch, "layers")) {
                List<Object> layers = new ArrayList<>();
                int i = 0;
                for (Map<String, Object> la : D.maps(arch, "layers")) {
                    layers.add(D.of("name", la.get("name"), "color", "series-" + (i % 8 + 1),
                            "items", D.get(la, "components", List.of()), "note", D.get(la, "description", "")));
                    i++;
                }
                add("cur-layers", "Layers", List.of(D.of("type", "layers", "stage", "architecture", "layers", layers)));
            } else {
                add("cur-layers", "Layers", List.of(placeholder("architecture", "Layers")));
            }
            add("cur-crud", "Who changes which data", List.of(crudHeatmap()),
                    "From the SQL each component runs: letters are create, read, update, delete.");

            Map<String, Object> byType = counts("counts.integrations.by_type");
            List<String> labels = byCount(byType);
            List<List<Object>> intRows = new ArrayList<>();
            List<Map<String, Object>> ints = D.maps(s, "integrations");
            for (Map<String, Object> i : ints.subList(0, Math.min(8, ints.size()))) {
                String target = D.str(i, "target") + (D.t(i, "endpoints") ? " (" + D.list(i, "endpoints").size() + " grouped)" : "");
                intRows.add(row(i.get("type"), target, D.get(i, "direction", ""), D.get(i, "usages", 0L)));
            }
            add("cur-integrations", "Integrations", List.of(
                    chart("bar", "Integrations by type", labels, values(byType, labels), "integrations"),
                    table(List.of("Type", "Target", "Direction", "Usages"), intRows)));

            List<Map<String, Object>> web = D.maps(s, "web_services");
            if (!web.isEmpty()) {
                Map<String, Object> byStyle = counts("counts.web_services.by_style");
                List<String> wl = new ArrayList<>();
                for (String side : List.of("offered", "called")) {
                    for (String st : List.of("REST", "SOAP")) {
                        wl.add(st + " " + side);
                    }
                }
                List<Object> wv = new ArrayList<>();
                wl.forEach(k -> wv.add(D.get(byStyle, k, 0L)));
                List<List<Object>> rows = new ArrayList<>();
                for (Map<String, Object> w : web.subList(0, Math.min(8, web.size()))) {
                    rows.add(row(w.get("side"), w.get("style"), w.get("name"), D.get(w, "operations", 0L)));
                }
                add("cur-web-services", "Web services (REST and SOAP)", List.of(
                        D.of("type", "kpis", "items", List.of(
                                kpi("Offered", "counts.web_services.offered"),
                                kpi("Called", "counts.web_services.called"),
                                kpi("Operations", "counts.web_services.operations"))),
                        chart("bar", "Web services by style", wl, wv, "web services"),
                        table(List.of("Offered / called", "Style", "Web service", "Operations"), rows)),
                        "Offered: other systems call the application; called: it calls them. "
                                + "Every operation is in the technical report, Web services page.");
            }

            List<Map<String, Object>> blocks = new ArrayList<>();
            blocks.add(D.of("type", "kpis", "items", List.of(
                    kpi("Tables", "counts.db_objects.table"),
                    kpi("Stored procedures", "counts.stored_procedures"),
                    kpi("SQL statements", "counts.sql.total"),
                    kpi("Distinct SQL", "counts.sql.distinct"))));
            List<List<Object>> tops = new ArrayList<>();
            List<Map<String, Object>> topTables = D.maps(s, "top_tables");
            for (Map<String, Object> t : topTables.subList(0, Math.min(8, topTables.size()))) {
                tops.add(row(t.get("name"), String.join("", D.strings(t, "crud")), D.get(t, "references", 0L)));
            }
            blocks.add(table(List.of("Table", "CRUD", "Referenced by"), tops, "Most used tables"));
            if (!rd.auditOnly()) {
                blocks.add(gradeChart("SQL by PostgreSQL grade"));
            }
            add("cur-data", "Data landscape", blocks);
        }

        Map<String, Object> gradeChart(String title) {
            Map<String, Object> grades = counts("counts.pg_grades");
            List<String> gl = GRADES.stream().filter(g -> D.t(grades, g)).toList();
            return chart("doughnut", title, gl, values(grades, gl), "statements", gl.stream().map(GRADE_TOKEN::get).toList());
        }

        void technical() {
            start("technical");
            Map<String, Object> cx = counts("counts.complexity.services");
            List<String> order = List.of("Simple", "Medium", "Complex", "Very complex");
            List<String> cl = new ArrayList<>(order.stream().filter(c -> D.t(cx, c)).toList());
            List<String> extra = new ArrayList<>(cx.keySet());
            extra.removeAll(order);
            cl.addAll(sortedStrings(new TreeSet<>(extra)));
            List<List<Object>> svc = new ArrayList<>();
            List<Map<String, Object>> top = D.maps(s, "top_services");
            for (Map<String, Object> x : top.subList(0, Math.min(8, top.size()))) {
                svc.add(row(x.get("name"), x.get("kind"), D.get(x, "component", ""), D.get(x, "tables", 0L)));
            }
            add("tech-services", "Services by complexity", List.of(
                    chart("bar", "Services by complexity band", cl, values(cx, cl), "services"),
                    table(List.of("Service", "Kind", "Component", "Tables"), svc, "Largest services")));

            Map<String, Object> dm = stage("data_migration");
            Map<String, Object> strategy = dm != null && D.t(dm, "strategy")
                    ? D.of("type", "text", "text", dm.get("strategy"), "stage", "data_migration")
                    : placeholder("data_migration", "Data strategy");
            addUnless(rd.auditOnly(), "tech-sql", "SQL readiness for PostgreSQL", List.of(
                    D.of("type", "kpis", "items", List.of(
                            kpi("SQL statements", "counts.sql.total"),
                            kpi("Built at run time", "counts.sql.dynamic"),
                            kpi("Not parsed", "counts.sql.not_parsed"))),
                    gradeChart("PostgreSQL grade"),
                    strategy), "");

            List<Map<String, Object>> jobs = rd.items("batch_jobs");
            Map<String, Object> coverage = counts("coverage");
            List<Object> gaps = new ArrayList<>();
            for (Map.Entry<String, Object> en : coverage.entrySet()) {
                if ((en.getKey().equals("autosys") || en.getKey().equals("crontab")) && Py.str(en.getValue()).contains("not provided")) {
                    gaps.add(en.getValue());
                }
            }
            List<Map<String, Object>> batch = new ArrayList<>();
            if (!jobs.isEmpty()) {
                Counter sched = new Counter();
                for (Map<String, Object> j : jobs) {
                    if (!"box".equals(j.get("kind"))) {
                        sched.add(D.t(j, "scheduler") ? D.str(j, "scheduler") : "unknown");
                    }
                }
                List<String> sl = byCount(sched);
                batch.add(chart("bar", "Batch jobs by scheduler", sl, values(sched, sl), "jobs"));
            } else {
                batch.add(D.of("type", "text", "text", "The scan found no scheduled batch jobs."));
            }
            List<Object> loose = new ArrayList<>();
            for (Flows.Flow f : Flows.batchFlows(rd)) {
                Object sc = D.get(f.schedule(), "scheduler");
                if ("none".equals(sc) || "event".equals(sc)) {
                    loose.add(f.name() + ": " + f.summary());
                }
            }
            if (!loose.isEmpty()) {
                batch.add(D.of("type", "bullets", "title", "Batch work with no scheduler definition",
                        "items", new ArrayList<>(loose.subList(0, Math.min(MAX_ROWS, loose.size())))));
            }
            if (!gaps.isEmpty()) {
                batch.add(D.of("type", "bullets", "title", "Inputs not provided", "items", gaps));
            }
            add("tech-batch", "Batch landscape", batch);

            List<Map<String, Object>> libs = rd.items("libraries");
            long vulnerable = libs.stream().filter(lb -> D.t(lb, "cves")).count();
            long remaining = libs.stream().filter(lb -> D.t(lb, "cves")
                    && !RESOLVING_ACTIONS.contains(Py.str(D.get(D.or(lb.get("recommendation"), Map.of()), "action")))).count();
            Map<String, Object> dp = stage("dependency_plan");
            Map<String, Object> themes;
            if (dp != null) {
                List<Object> items = new ArrayList<>();
                for (Map<String, Object> t : D.maps(dp, "themes")) {
                    items.add(D.str(t, "name") + ": " + D.str(t, "action") + (D.t(t, "wave") ? " (" + D.str(t, "wave") + ")" : ""));
                }
                themes = D.of("type", "bullets", "stage", "dependency_plan", "items", items);
            } else {
                themes = placeholder("dependency_plan", "Upgrade themes");
            }
            add("tech-security", "Dependency security: exposure today and after migration", List.of(
                    D.of("type", "kpis", "items", List.of(
                            kpi("Vulnerable libraries", "counts.vulnerable_libraries.total"),
                            kpi("Known vulnerabilities", "counts.vulnerable_libraries.vulnerabilities"),
                            kpi("End-of-life libraries", "counts.eol_libraries"))),
                    chart("bar", "Vulnerable libraries", List.of("Today", "After recommended upgrades"),
                            List.of(vulnerable, remaining), "libraries", List.of("critical", "good")),
                    themes),
                    "'After' counts vulnerable libraries whose recommendation is not upgrade, replace or "
                            + "remove, i.e. what remains if every recommendation is applied.");

            List<Map<String, Object>> cm = D.maps(s, "component_metrics");
            Map<String, Object> lang = counts("by_language");
            List<String> ll = new ArrayList<>(lang.keySet().stream().filter(k -> D.t(lang.get(k), "loc_code")).toList());
            ll.sort((a, b) -> Double.compare(D.num(D.get(lang.get(b), "loc_code")), D.num(D.get(lang.get(a), "loc_code"))));
            ll = new ArrayList<>(ll.subList(0, Math.min(8, ll.size())));
            List<Object> locs = new ArrayList<>();
            ll.forEach(k -> locs.add(D.get(lang.get(k), "loc_code")));
            List<List<Object>> qrows = new ArrayList<>();
            for (Map<String, Object> m : cm) {
                qrows.add(row(m.get("component"), D.get(m, "complexity", ""), D.get(m, "complexity_avg", 0L),
                        D.get(m, "duplication_pct", 0L), D.get(m, "dead_code_candidates", 0L), D.get(m, "test_ratio", 0L)));
            }
            add("tech-quality", "Code quality", List.of(
                    table(List.of("Component", "Complexity", "Avg. complexity", "Duplication %", "Dead-code candidates", "Test ratio"), qrows),
                    chart("bar", "Lines of code by language", ll, locs, "lines")));

            List<List<Object>> frows = new ArrayList<>();
            List<Map<String, Object>> tf = D.maps(s, "top_findings");
            for (Map<String, Object> f : tf.subList(0, Math.min(5, tf.size()))) {
                frows.add(row(f.get("severity"), f.get("rule"), f.get("message")));
            }
            add("tech-findings", "Findings", List.of(severityChart("Findings by severity"),
                    table(List.of("Severity", "Rule", "Finding"), frows, "Top findings")));

            List<List<Object>> cov = new ArrayList<>();
            for (Map<String, Object> c : D.maps(rd.run, "coverage")) {
                cov.add(row(D.t(c, "label") ? c.get("label") : D.get(c, "kind", ""), D.get(c, "status", ""), D.get(c, "file_count", 0L)));
            }
            add("tech-coverage", "Input coverage", List.of(table(List.of("Input", "Status", "Files"), cov)),
                    "A missing input is a coverage gap, not an absence: the scan cannot see what was not provided.");
        }

        void target() {
            start("target");
            Map<String, Object> ta = stage("target_architecture");
            List<Map<String, Object>> blocks = new ArrayList<>();
            if (ta != null) {
                List<List<Object>> rows = new ArrayList<>();
                for (Map<String, Object> c : D.maps(ta, "components")) {
                    rows.add(row(c.get("name"), c.get("technology"), c.get("platform"), String.join(", ", D.strings(c, "replaces"))));
                }
                blocks.add(staged(table(List.of("Target component", "Technology", "Platform", "Replaces"), rows), "target_architecture"));
                if (D.t(ta, "diagrams")) {
                    Map<String, Object> d = D.maps(ta, "diagrams").get(0);
                    blocks.add(D.of("type", "diagram", "title", d.get("title"), "mermaid", d.get("mermaid"), "stage", "target_architecture"));
                }
            } else {
                blocks.add(placeholder("target_architecture", "Target architecture"));
            }
            add("tgt-architecture", "Target architecture", blocks, narrative("architect"));
            Map<String, Object> trans;
            if (ta != null) {
                TreeSet<String> legacy = new TreeSet<>(PyJson::compareCodePoints);
                D.maps(ta, "components").forEach(c -> legacy.addAll(D.strings(c, "replaces")));
                List<Object> today = new ArrayList<>();
                legacy.forEach(n -> today.add(D.of("key", "o:" + n, "label", n, "kind", "muted")));
                List<Object> tgt = new ArrayList<>();
                List<Object> edges = new ArrayList<>();
                for (Map<String, Object> c : D.maps(ta, "components")) {
                    tgt.add(D.of("key", "t:" + D.str(c, "name"), "label",
                            D.str(c, "name") + "\n" + D.str(c, "technology") + " · " + D.str(c, "platform"), "kind", "service"));
                }
                for (Map<String, Object> c : D.maps(ta, "components")) {
                    for (String r : D.strings(c, "replaces")) {
                        edges.add(D.of("from", "o:" + r, "to", "t:" + D.str(c, "name"), "label", "becomes"));
                    }
                }
                trans = D.of("type", "flow", "title", "From today to the target", "stage", "target_architecture",
                        "columns", List.of(D.of("title", "Today", "nodes", today), D.of("title", "Target on Google Cloud", "nodes", tgt)),
                        "edges", edges);
                Diagrams.Diagram d = Diagrams.currentToTarget(rd, ta);
                trans.put("mermaid", d != null ? d.source() : "");
            } else {
                trans = placeholder("target_architecture", "From today to the target");
            }
            add("tgt-transition", "From today to the target", List.of(trans));
            List<List<Object>> mapping = new ArrayList<>();
            for (Map<String, Object> m : rd.targetMapping) {
                mapping.add(row(D.get(m, "legacy", ""), D.get(m, "target", "")));
            }
            add("tgt-mapping", "Legacy to target mapping", List.of(table(List.of("Legacy", "Target"), mapping)),
                    "Mapping rules from rules/target-mapping.yaml for " + Py.str(D.get(rd.target, "label", "")) + ".");
        }

        static double effort(Map<String, Object> w) {
            double sum = 0;
            for (Map<String, Object> f : D.maps(w, "effort")) {
                Object v = f.get("value");
                if (v instanceof Number n) {
                    sum += n.doubleValue();
                } else if (v instanceof Boolean b) {
                    sum += b ? 1 : 0;
                }
            }
            return sum;
        }

        void plan() {
            start("plan");
            Map<String, Object> plan = stage("plan");
            if (plan == null) {
                add("plan-roadmap", "Roadmap", List.of(placeholder("plan", "Roadmap")));
                add("plan-waves", "Waves and roadmap", List.of(placeholder("plan", "Waves")));
                add("plan-team", "Team and decisions needed", List.of(placeholder("plan", "Team and decisions")));
                return;
            }
            List<Map<String, Object>> waves = new ArrayList<>(D.maps(plan, "waves"));
            waves.sort((a, b) -> com.legacyaudit.reports.jinja.Values.compare(a.get("order"), b.get("order")));
            Map<String, Object> g = Visuals.gantt(waves);
            List<Object> bars = new ArrayList<>();
            for (Map<String, Object> b : D.maps(g, "bars")) {
                bars.add(D.of("name", b.get("name"), "goal", b.get("goal"), "start", b.get("start"), "end", b.get("end"),
                        "color", b.get("color")));
            }
            add("plan-roadmap", "Roadmap", List.of(D.of("type", "gantt", "stage", "plan", "months", g.get("months"), "bars", bars)),
                    "Waves, start month and duration from the plan stage.");

            List<List<Object>> rows = new ArrayList<>();
            double total = 0;
            List<Object> cumulative = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Map<String, Object> w : waves) {
                long startM = D.i(w, "start_month");
                rows.add(row(w.get("name"), w.get("goal"), startM + " to " + (startM + D.i(w, "duration_months")), num(effort(w))));
                total += effort(w);
                cumulative.add(PyNum.round(total, 1));
                names.add(D.str(w, "name"));
            }
            add("plan-waves", "Waves and roadmap", List.of(
                    staged(table(List.of("Wave", "Goal", "Months", "Effort (person-days)"), rows), "plan"),
                    chart("line", "Cumulative effort by wave", names, cumulative, "person-days")),
                    "Wave effort is the plan's split of effort.json by component; a split marked as an "
                            + "estimate in the enrichment is the agent's.");
            List<List<Object>> team = new ArrayList<>();
            D.maps(plan, "team").forEach(t -> team.add(row(t.get("role"), t.get("count"))));
            add("plan-team", "Team and decisions needed", List.of(
                    staged(table(List.of("Role", "People"), team), "plan"),
                    D.of("type", "bullets", "title", "Decisions needed", "stage", "plan", "items", plan.get("decisions_needed"))));
        }

        void risks() {
            start("risks");
            Map<String, Object> risks = stage("risks");
            Map<String, Object> block;
            if (risks != null) {
                List<List<Object>> rows = new ArrayList<>();
                for (Map<String, Object> r : sortedRisks(risks)) {
                    rows.add(row(r.get("id"), r.get("title"), r.get("severity"), r.get("likelihood"), r.get("mitigation"),
                            D.get(r, "owner", "")));
                }
                block = staged(table(List.of("ID", "Risk", "Severity", "Likelihood", "Mitigation", "Owner"), rows), "risks");
            } else {
                block = placeholder("risks", "Risk register");
            }
            add("risk-register", "Risk register", List.of(block));
            Map<String, Object> heat;
            if (risks != null) {
                Map<String, Object> rm = Visuals.riskMatrix(D.maps(risks, "risks"));
                List<String> cols = new ArrayList<>();
                D.strings(rm, "columns").forEach(c -> cols.add("likelihood " + c));
                List<Object> rows = new ArrayList<>();
                for (Map<String, Object> r : D.maps(rm, "rows")) {
                    List<Object> cells = new ArrayList<>();
                    for (Map<String, Object> c : D.maps(r, "cells")) {
                        List<String> ids = new ArrayList<>();
                        D.list(c, "ids").forEach(x -> ids.add(Py.str(x)));
                        cells.add(D.of("text", String.join(" ", ids), "level", c.get("level")));
                    }
                    rows.add(D.of("label", r.get("severity"), "cells", cells));
                }
                heat = staged(heatmap("Risks by severity and likelihood", cols, rows, "risk",
                        "Each cell lists the risk ids; red = act first."), "risks");
            } else {
                heat = placeholder("risks", "Risk heat map");
            }
            add("risk-heatmap", "Risk heat map", List.of(heat));

            Map<String, Object> asm = stage("assumptions");
            List<Map<String, Object>> blocks = new ArrayList<>();
            if (asm != null) {
                List<Object> items = new ArrayList<>();
                for (Map<String, Object> a : D.maps(asm, "assumptions")) {
                    items.add(D.str(a, "id") + ": " + D.str(a, "statement") + " (owner: " + Py.str(D.get(a, "owner", "TBD")) + ")");
                }
                blocks.add(D.of("type", "bullets", "title", "Assumptions", "stage", "assumptions", "items", items));
                blocks.add(D.of("type", "bullets", "title", "Open questions", "stage", "assumptions",
                        "items", D.get(asm, "open_questions", List.of())));
            } else {
                blocks.add(placeholder("assumptions", "Assumptions and open questions"));
            }
            add("risk-assumptions", "Assumptions and open questions", blocks);

            List<Object> steps = new ArrayList<>(D.list(stage("plan"), "next_steps"));
            if (D.t(status, "missing")) {
                steps.add("Complete the enrichment: " + String.join(", ", D.strings(status, "missing")));
            }
            if (D.t(status, "stale")) {
                steps.add("Refresh stale enrichment (/audit-enrich stale): " + String.join(", ", D.strings(status, "stale")));
            }
            List<String> gaps = new ArrayList<>();
            for (Map<String, Object> c : D.maps(rd.run, "coverage")) {
                if ("missing".equals(c.get("status"))) {
                    gaps.add(D.t(c, "label") ? D.str(c, "label") : Py.str(D.get(c, "kind", "")));
                }
            }
            if (!gaps.isEmpty()) {
                steps.add("Provide missing inputs: " + String.join(", ", gaps));
            }
            add("risk-next", "Next steps", List.of(D.of("type", "bullets", "items", steps.isEmpty() ? List.of("None.") : steps)));
        }
    }
}
