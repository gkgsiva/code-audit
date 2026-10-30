package com.legacyaudit.reports;

import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.reports.jinja.Values;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code legacy-audit report --type functional}: the functional document, for business readers.
 *
 * <p>A static, offline HTML site like the technical report (same theme, vendored Chart.js and
 * Mermaid, evidence links), written to {@code runs/<id>/reports/functional/}. It reads two things:
 *
 * <ul>
 *   <li>the <b>scan facts</b>, for every count, every screen and batch flow, the CRUD heat map, the
 *       batch calendar and the interface map;
 *   <li>the <b>validated enrichment</b> ({@code functional}, {@code classification}, {@code flows},
 *       {@code overview}, {@code assumptions}, {@code narratives}), for the words: capabilities,
 *       actors, use cases, business rules, data entities, one plain-language story per screen and
 *       per batch, glossary.
 * </ul>
 *
 * <p>A part whose enrichment stage is missing shows a placeholder naming the prompt to run, and the
 * scan-only parts still build, so the document can be built straight after a scan.
 */
public final class FunctionalReport {

    static final List<List<String>> FN_NAV = List.of(
            List.of("summary", "index.html", "Summary"),
            List.of("use-cases", "use-cases.html", "Users and use cases"),
            List.of("business-flows", "business-flows.html", "Business flows"),
            List.of("screens", "screens.html", "Screens"),
            List.of("batch", "batch.html", "Batch"),
            List.of("rules", "rules.html", "Business rules"),
            List.of("data", "data.html", "Data"),
            List.of("interfaces", "interfaces.html", "Interfaces"),
            List.of("glossary", "glossary.html", "Glossary and questions"));
    static final String FOOTER = "Counts, flows, tables and schedules come from the scan of this run (deterministic scripts). "
            + "Descriptions in plain words were written by the Copilot agent and checked against the scan "
            + "by legacy-audit validate; each cites the code it is based on or is marked as an assumption.";
    static final Map<String, String> RULE_COLOR = Map.of(
            "validation", "c1", "calculation", "c7", "eligibility", "c3", "status-change", "c4", "limit", "c8",
            "notification", "c2", "other", "c5");
    static final Map<String, List<String>> ACTOR_STYLE = Map.of(
            "user", List.of("users", "c1"), "external-system", List.of("interfaces", "c2"),
            "scheduler", List.of("calendar", "c4"), "operator", List.of("services", "c7"));
    private static final List<String> STEP_STYLES = List.of(
            "fill:#dbe9fb,stroke:#2a78d6,color:#0b0b0b",
            "fill:#ece3fb,stroke:#6250d6,color:#0b0b0b",
            "fill:#dcf3dc,stroke:#0ca30c,color:#0b0b0b",
            "fill:#fde7d9,stroke:#ec835a,color:#0b0b0b",
            "fill:#d8f1f0,stroke:#13867f,color:#0b0b0b");

    private FunctionalReport() {}

    /** Build the functional document for a run; returns its {@code index.html}. */
    public static Path build(Path runDir, Path inputsDir) {
        RunData rd = RunData.load(runDir, inputsDir);
        Path out = runDir.resolve("reports").resolve("functional");
        buildInto(rd, out);
        Path latest = TechReport.latestCopyOf(runDir, rd);
        if (latest != null) {
            buildInto(rd, latest.resolve("reports").resolve("functional"));
        }
        return out.resolve("index.html");
    }

    public static void buildInto(RunData rd, Path out) {
        try {
            Fmt.rmtree(out);
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Fmt.copyResources("com/legacyaudit/reports/assets", TechReport.ASSETS, out.resolve("assets"));
        FnPages p = new FnPages(rd, out);
        p.summary();
        p.useCases();
        p.businessFlows();
        p.screens();
        p.batch();
        p.rules();
        p.data();
        p.interfaces();
        p.glossary();
    }

    static final class FnPages extends TechReport.Pages {
        final Map<String, Object> fn;
        final Map<String, Map<String, Object>> screenStory = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> batchStory = new LinkedHashMap<>();

        FnPages(RunData rd, Path out) {
            super(rd, out, new LinkedHashMap<>(), TechReport.env(), Status.loadEnrichment(rd.runDir));
            List<Object> nav = new ArrayList<>();
            for (List<String> n : FN_NAV) {
                nav.add(D.of("id", n.get(0), "href", n.get(1), "title", n.get(2)));
            }
            common.put("nav", nav);
            common.put("doc_kind", "Functional document");
            common.put("footer_note", FOOTER);
            fn = D.map(e, "functional");
            for (Map<String, Object> s : D.maps(fn, "screens")) {
                screenStory.put(D.str(s, "screen_id"), s);
            }
            for (Map<String, Object> b : D.maps(fn, "batches")) {
                batchStory.put(D.str(b, "job_id"), b);
            }
        }

        // ---- helpers --------------------------------------------------------------------------

        Map<String, Object> cites(Map<String, Object> item) {
            List<Object> links = new ArrayList<>();
            for (Map<String, Object> ev : D.maps(item, "evidence")) {
                links.add(link(D.get(ev, "file"), D.get(ev, "line")));
            }
            return D.with(item, "links", links);
        }

        List<String> names(List<String> ids) {
            List<String> res = new ArrayList<>();
            ids.forEach(i -> res.add(D.last(rd.label(i), "/")));
            return res;
        }

        Map<String, String> flowHref() {
            Map<String, String> res = new LinkedHashMap<>();
            List<Flows.Flow> all = new ArrayList<>(screenFlows);
            all.addAll(batchFlows);
            all.forEach(f -> res.put(f.id(), "flows/" + f.slug() + ".html"));
            return res;
        }

        boolean missing(String stage) {
            return !D.t(e, stage);
        }

        List<Object> actors() {
            List<Object> actors = new ArrayList<>();
            for (Map<String, Object> a : D.maps(fn, "actors")) {
                List<String> st = ACTOR_STYLE.get(D.str(a, "kind"));
                if (st == null) {
                    throw new IllegalArgumentException("unknown actor kind: " + a.get("kind"));
                }
                actors.add(D.with(a, "icon", st.get(0), "color", st.get(1)));
            }
            return actors;
        }

        // ---- pages ----------------------------------------------------------------------------

        void summary() {
            List<Map<String, Object>> tiles = new ArrayList<>();
            tiles.add(tile("counts.screens.total", "Screens", "screens.html", ""));
            tiles.add(tile("counts.services.total", "Services (operations)", "", ""));
            tiles.add(tile("counts.batch_jobs.total", "Batch jobs", "batch.html", ""));
            tiles.add(tile("counts.db_objects.table", "Tables", "data.html", ""));
            tiles.add(tile("counts.integrations.total", "Interfaces", "interfaces.html", ""));
            tiles.add(tile("totals.components", "Components", "", ""));
            tiles.get(3).put("icon", "data");
            tiles.get(3).put("color", "c6");
            Map<String, Object> ov = D.map(e, "overview");
            Object narrative = D.get(D.map(D.map(e, "narratives"), "functional"), "text", "");
            Map<String, long[]> perComp = new LinkedHashMap<>();
            java.util.function.Function<Map<String, Object>, long[]> slot = item ->
                    perComp.computeIfAbsent(D.t(item, "component") ? D.str(item, "component") : "(none)", k -> new long[3]);
            for (Map<String, Object> scr : rd.items("screens")) {
                if (!D.t(scr, "partial")) {
                    slot.apply(scr)[0]++;
                }
            }
            for (Map<String, Object> svc : rd.items("services")) {
                slot.apply(svc)[1]++;
            }
            for (Map<String, Object> job : rd.items("batch_jobs")) {
                if (!"box".equals(D.get(job, "kind"))) {
                    slot.apply(job)[2]++;
                }
            }
            List<String> names = new ArrayList<>(perComp.keySet());
            names.sort((a, b) -> Long.compare(sum(perComp.get(b)), sum(perComp.get(a))));
            names = names.subList(0, Math.min(12, names.size()));
            List<Map<String, Object>> datasets = new ArrayList<>();
            String[][] sets = {{"Screens", "series-1"}, {"Services", "series-7"}, {"Batch jobs", "series-4"}};
            for (int k = 0; k < 3; k++) {
                List<Object> vals = new ArrayList<>();
                for (String n : names) {
                    vals.add(perComp.get(n)[k]);
                }
                datasets.add(D.of("label", sets[k][0], "values", vals, "color", sets[k][1]));
            }
            Map<String, Object> mixChart = new Visuals.Chart("mix", "What each part of the application offers", "stacked", names)
                    .datasets(datasets).category("Component").horizontal(true).note("Counts from the scan, per component.").build();
            List<Object> keyPoints = new ArrayList<>();
            D.maps(ov, "key_points").forEach(k -> keyPoints.add(cites(k)));
            render("fn_summary.html", "index.html", "summary", "What the application does", D.of("tiles", tiles,
                    "fn_summary", D.or(D.or(D.get(fn, "summary"), D.get(ov, "purpose")), ""),
                    "from_overview", !D.t(fn, "summary") && D.t(ov, "purpose"), "users", D.get(ov, "users", new ArrayList<>()),
                    "domains", domains(), "actors", actors(), "narrative", narrative, "key_points", keyPoints,
                    "mix_chart", mixChart, "missing_functional", missing("functional"), "charts", true));
        }

        private static long sum(long[] v) {
            return v[0] + v[1] + v[2];
        }

        /**
         * Capability map: the agent's domains, else components grouped by the scanner's type
         * (labelled as such).
         */
        List<Object> domains() {
            Map<String, String> href = flowHref();
            List<Object> res = new ArrayList<>();
            if (D.t(fn, "domains")) {
                List<Map<String, Object>> domains = D.maps(fn, "domains");
                for (int i = 0; i < domains.size(); i++) {
                    Map<String, Object> d = domains.get(i);
                    List<Object> caps = new ArrayList<>();
                    for (Map<String, Object> cap : D.maps(d, "capabilities")) {
                        List<String> ids = new ArrayList<>(D.strings(cap, "screen_ids"));
                        ids.addAll(D.strings(cap, "job_ids"));
                        List<String> ns = names(ids);
                        List<Object> links = new ArrayList<>();
                        for (int k = 0; k < ids.size(); k++) {
                            links.add(D.of("name", ns.get(k), "href", href.get(ids.get(k))));
                        }
                        caps.add(D.with(cites(cap), "flows", links, "n_services", (long) D.list(cap, "service_ids").size()));
                    }
                    res.add(D.with(d, "caps", caps, "color", "c" + (i % 8 + 1), "from_scan", false));
                }
                return res;
            }
            Map<String, Map<String, Object>> classes = new LinkedHashMap<>();
            for (Map<String, Object> c : D.maps(D.map(e, "classification"), "components")) {
                classes.put(D.str(c, "component"), c);
            }
            Map<String, List<Object>> groups = new TreeMap<>(PyJson::compareCodePoints);
            for (Map<String, Object> comp : rd.items("components")) {
                Map<String, Object> cl = classes.get(D.str(comp, "name"));
                String key = cl != null ? D.str(cl, "capability") : D.t(comp, "type") ? D.str(comp, "type") : "unknown";
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(D.of("name", comp.get("name"),
                        "description", Py.str(D.get(comp, "files", 0L)) + " files, " + Fmt.num(D.get(comp, "loc_code", 0L)) + " code lines",
                        "flows", new ArrayList<>(), "links", new ArrayList<>()));
            }
            int i = 0;
            for (Map.Entry<String, List<Object>> g : groups.entrySet()) {
                res.add(D.of("name", g.getKey(), "description", "", "caps", g.getValue(), "color", "c" + (i % 8 + 1),
                        "from_scan", classes.isEmpty()));
                i++;
            }
            return res;
        }

        void useCases() {
            Map<String, String> href = flowHref();
            List<Map<String, Object>> ucs = new ArrayList<>();
            for (Map<String, Object> u : D.maps(fn, "use_cases")) {
                List<String> ids = D.strings(u, "screen_ids");
                List<String> ns = names(ids);
                List<Object> screens = new ArrayList<>();
                for (int k = 0; k < ids.size(); k++) {
                    screens.add(D.of("name", ns.get(k), "href", href.get(ids.get(k))));
                }
                ucs.add(D.with(cites(u), "screens", screens));
            }
            List<Object> actors = actors();
            Map<String, Object> diagram = null;
            if (!ucs.isEmpty()) {
                List<String> lines = new ArrayList<>(List.of("flowchart LR"));
                Map<String, String> aid = new LinkedHashMap<>();
                for (int i = 0; i < actors.size(); i++) {
                    aid.put(D.str(actors.get(i), "name"), "a" + i);
                }
                for (Object a : actors) {
                    lines.add("  " + aid.get(D.str(a, "name")) + "([" + Diagrams.label(D.get(a, "name")) + "]):::actor");
                }
                for (int i = 0; i < ucs.size(); i++) {
                    Map<String, Object> u = ucs.get(i);
                    lines.add("  u" + i + "(" + Diagrams.label(D.str(u, "id") + " " + D.str(u, "name")) + "):::usecase");
                    if (aid.containsKey(D.str(u, "actor"))) {
                        lines.add("  " + aid.get(D.str(u, "actor")) + " --> u" + i);
                    }
                }
                lines.add("  classDef actor fill:#fff1cc,stroke:#d69a00,color:#0b0b0b\n"
                        + "  classDef usecase fill:#ece3fb,stroke:#6250d6,color:#0b0b0b");
                diagram = D.of("title", "Who does what", "source", String.join("\n", lines), "nodes", (long) (actors.size() + ucs.size()),
                        "note", "Actors (left) and the use cases they start, from the enrichment.");
            }
            render("fn_use_cases.html", "use-cases.html", "use-cases", "Users and use cases", D.of("actors", actors,
                    "use_cases", ucs, "diagram", diagram, "missing_functional", missing("functional"), "diagrams", true));
        }

        void businessFlows() {
            List<Object> res = new ArrayList<>();
            for (Map<String, Object> f : D.maps(D.map(e, "flows"), "flows")) {
                List<Map<String, Object>> steps = new ArrayList<>(D.maps(f, "steps"));
                steps.sort((a, b) -> Values.compare(a.get("order"), b.get("order")));
                List<String> lines = new ArrayList<>(List.of("flowchart LR"));
                String prev = null;
                for (int k = 0; k < steps.size(); k++) {
                    Map<String, Object> st = steps.get(k);
                    lines.add("  s" + k + "[" + Diagrams.label(Py.str(st.get("order")) + ". " + Py.str(st.get("description"))) + "]");
                    lines.add("  style s" + k + " " + STEP_STYLES.get(k % STEP_STYLES.size()));
                    if (prev != null) {
                        lines.add("  " + prev + " --> s" + k);
                    }
                    prev = "s" + k;
                }
                if (D.t(f, "trigger")) {
                    lines.add("  t0{{" + Diagrams.label(f.get("trigger")) + "}} --> s0");
                    lines.add("  style t0 fill:#fff1cc,stroke:#d69a00,color:#0b0b0b");
                }
                List<Object> stepOut = new ArrayList<>();
                steps.forEach(st -> stepOut.add(D.with(st, "facts", names(D.strings(st, "fact_ids")))));
                res.add(D.with(cites(f), "steps", stepOut, "diagram", D.of("title", f.get("name"), "source", String.join("\n", lines),
                        "nodes", (long) steps.size(), "note", "")));
            }
            render("fn_business_flows.html", "business-flows.html", "business-flows", "Business flows", D.of("flows_", res,
                    "missing", missing("flows"), "diagrams", true));
        }

        void flowPages(List<Flows.Flow> items, Map<String, Map<String, Object>> stories) {
            for (Flows.Flow f : items) {
                Map<String, Object> ctx = flowCtx(f, stories.get(f.id()));
                ctx.put("audience", "functional");
                ctx.put("diagrams", true);
                render("flow.html", "flows/" + f.slug() + ".html", f.kind().equals("screen") ? "screens" : "batch",
                        (f.kind().equals("screen") ? "Screen" : "Batch") + ": " + f.name(), ctx);
            }
        }

        void screens() {
            flowPages(screenFlows, screenStory);
            Set<String> real = new HashSet<>();
            screenFlows.forEach(f -> real.add(f.id()));
            List<String> lines = new ArrayList<>(List.of("flowchart LR"));
            List<Flows.Flow> shown = screenFlows.subList(0, Math.min(Diagrams.MAX_NODES, screenFlows.size()));
            Map<String, String> ids = new LinkedHashMap<>();
            for (int i = 0; i < shown.size(); i++) {
                ids.put(shown.get(i).id(), "s" + i);
            }
            for (Flows.Flow f : shown) {
                lines.add("  " + ids.get(f.id()) + "[" + Diagrams.label(f.name()) + "]:::screen");
            }
            int nEdges = 0;
            Map<String, String> byName = new LinkedHashMap<>();
            screenFlows.forEach(f -> byName.put(f.name(), f.id()));
            for (Flows.Flow f : shown) {
                for (String nxt : f.after()) {
                    String target = byName.get(nxt);
                    if (target != null && ids.containsKey(target) && real.contains(target)) {
                        lines.add("  " + ids.get(f.id()) + " --> " + ids.get(target));
                        nEdges++;
                    }
                }
            }
            lines.add(Flows.MERMAID_CLASSES);
            String note = (screenFlows.size() > Diagrams.MAX_NODES
                    ? "Showing " + Diagrams.MAX_NODES + " of " + screenFlows.size() + " screens. " : "")
                    + (nEdges > 0 ? "" : "No links between screens were found by the scan.");
            Map<String, Object> navMap = D.of("title", "How the screens connect", "source", String.join("\n", lines),
                    "nodes", (long) ids.size(), "note", note);
            render("fn_screens.html", "screens.html", "screens", "Screens", D.of("screen_flows", screenFlows, "stories", screenStory,
                    "nav_map", navMap, "missing_functional", missing("functional"), "diagrams", true));
        }

        void batch() {
            flowPages(batchFlows, batchStory);
            List<Object> gaps = new ArrayList<>();
            for (Map<String, Object> c : D.maps(rd.run, "coverage")) {
                Object kind = D.get(c, "kind");
                if (("autosys".equals(kind) || "crontab".equals(kind)) && "missing".equals(D.get(c, "status"))) {
                    gaps.add(D.get(c, "label"));
                }
            }
            render("fn_batch.html", "batch.html", "batch", "Batch", D.of("batch_flows", batchFlows, "batch_rows", batchRows,
                    "stories", batchStory, "said", batchSaid, "week", Flows.weekGrid(batchFlows), "days", Flows.DAY_NAMES,
                    "gaps", gaps, "missing_functional", missing("functional")));
        }

        void rules() {
            List<Object> rules = new ArrayList<>();
            Map<String, Long> kinds = new LinkedHashMap<>();
            for (Map<String, Object> r : D.maps(fn, "business_rules")) {
                rules.add(D.with(cites(r), "color", RULE_COLOR.getOrDefault(D.str(r, "kind"), "c5"),
                        "facts", names(D.strings(r, "fact_ids"))));
                kinds.merge(D.str(r, "kind"), 1L, Long::sum);
            }
            List<String> kl = new ArrayList<>(kinds.keySet());
            kl.sort((a, b) -> {
                int c = Long.compare(kinds.get(b), kinds.get(a));
                return c != 0 ? c : PyJson.compareCodePoints(a, b);
            });
            List<Object> values = new ArrayList<>();
            List<Object> colors = new ArrayList<>();
            for (String k : kl) {
                values.add(kinds.get(k));
                colors.add("series-" + Integer.parseInt(RULE_COLOR.getOrDefault(k, "c5").substring(1)));
            }
            Map<String, Object> chart = new Visuals.Chart("rule-kinds", "Business rules by kind", "doughnut", kl).values(values)
                    .colors(colors).label("Rules").category("Kind").build();
            render("fn_rules.html", "rules.html", "rules", "Business rules", D.of("rules", rules, "chart", chart,
                    "missing_functional", missing("functional"), "charts", true));
        }

        void data() {
            List<Object> entities = new ArrayList<>();
            for (Map<String, Object> d : D.maps(fn, "data_entities")) {
                entities.add(D.with(cites(d), "tables", names(D.strings(d, "db_object_ids"))));
            }
            List<Flows.Pair> pairs = Flows.tablesUsedTogether(rd);
            render("fn_data.html", "data.html", "data", "Business data", D.of("entities", entities, "matrix", Flows.crudMatrix(rd),
                    "data_map", D.of("title", "Tables used together", "source", Flows.dataMapMermaid(rd), "nodes", (long) pairs.size(),
                            "note", "Two tables are joined when the same statement uses both."),
                    "missing_functional", missing("functional"), "diagrams", true));
        }

        void interfaces() {
            Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
            for (Map<String, Object> i : D.maps(fn, "interfaces")) {
                raw.put(D.str(i, "integration_id"), i);
            }
            Map<String, Map<String, Object>> stories = rd.byRow(raw);
            List<Map<String, Object>> integ = new ArrayList<>();
            for (Map<String, Object> i : rd.items("integrations")) {
                Object t = D.get(i, "type");
                if (!("MQ-CHANNEL".equals(t) || "MQ-QMGR".equals(t)) || D.t(i, "endpoints")) {
                    integ.add(i); // a queue manager row holding its queues is an interface (decision 177)
                }
            }
            integ.sort((a, b) -> {
                int c = Double.compare(D.num(D.or(D.get(b, "usages"), 0L)), D.num(D.or(D.get(a, "usages"), 0L)));
                if (c != 0) {
                    return c;
                }
                c = PyJson.compareCodePoints(D.str(a, "type"), D.str(b, "type"));
                return c != 0 ? c : PyJson.compareCodePoints(D.str(a, "target"), D.str(b, "target"));
            });
            List<Map<String, Object>> shown = integ.subList(0, Math.min(Diagrams.MAX_NODES, integ.size()));
            Object app = common.get("app");
            List<String> lines = new ArrayList<>(List.of("flowchart LR", "  APP((" + Diagrams.label(app) + ")):::app"));
            for (int k = 0; k < shown.size(); k++) {
                Map<String, Object> i = shown.get(k);
                Map<String, Object> story = stories.get(D.str(i, "id"));
                Object name = D.or(D.get(story, "partner"), D.str(i, "type") + " " + D.str(i, "target"));
                lines.add("  p" + k + "([" + Diagrams.label(name) + "]):::" + ("DB".equals(i.get("type")) ? "db" : "ext"));
                String label = Diagrams.label(i.get("type"));
                Object d = D.get(i, "direction");
                if ("outbound".equals(d)) {
                    lines.add("  APP -->|" + label + "| p" + k);
                } else if ("inbound".equals(d)) {
                    lines.add("  p" + k + " -->|" + label + "| APP");
                } else if ("both".equals(d)) {
                    lines.add("  APP <-->|" + label + "| p" + k);
                } else {
                    lines.add("  APP -.-|" + label + "| p" + k);
                }
            }
            lines.add("  classDef app fill:#2a78d6,stroke:#1c5cab,color:#ffffff\n"
                    + "  classDef ext fill:#fde7d9,stroke:#ec835a,color:#0b0b0b\n"
                    + "  classDef db fill:#dcf3dc,stroke:#0ca30c,color:#0b0b0b");
            List<Object> rows = new ArrayList<>();
            Map<String, Long> byType = new LinkedHashMap<>();
            for (Map<String, Object> i : integ) {
                String id = D.str(i, "id");
                rows.add(D.with(i, "story", stories.containsKey(id) ? cites(stories.get(id)) : null, "link", link(rd.firstEvidence(i))));
                byType.merge(D.str(i, "type"), 1L, Long::sum);
            }
            List<String> tl = new ArrayList<>(byType.keySet());
            tl.sort((a, b) -> {
                int c = Long.compare(byType.get(b), byType.get(a));
                return c != 0 ? c : PyJson.compareCodePoints(a, b);
            });
            List<Object> values = new ArrayList<>();
            List<Object> colors = new ArrayList<>();
            for (int k = 0; k < tl.size(); k++) {
                values.add(byType.get(tl.get(k)));
                colors.add("series-" + (k % 8 + 1));
            }
            Map<String, Object> chart = new Visuals.Chart("int-types", "Interfaces by type", "doughnut", tl).values(values)
                    .colors(colors).label("Interfaces").category("Type").build();
            render("fn_interfaces.html", "interfaces.html", "interfaces", "Interfaces with other systems", D.of(
                    "context", D.of("title", "The application and the systems around it", "source", String.join("\n", lines),
                            "nodes", (long) (shown.size() + 1),
                            "note", integ.size() > Diagrams.MAX_NODES ? "Showing " + Diagrams.MAX_NODES + " of " + integ.size() + " interfaces." : ""),
                    "rows", rows, "chart", chart, "missing_functional", missing("functional"), "charts", true, "diagrams", true));
        }

        void glossary() {
            Map<String, Object> asm = D.map(e, "assumptions");
            List<Object> gaps = new ArrayList<>();
            for (Map<String, Object> c : D.maps(rd.run, "coverage")) {
                if ("missing".equals(D.get(c, "status"))) {
                    gaps.add(D.or(D.get(c, "label"), D.get(c, "kind", "")));
                }
            }
            List<Map<String, Object>> terms = new ArrayList<>(D.maps(fn, "glossary"));
            terms.sort((a, b) -> PyJson.compareCodePoints(D.str(a, "term").toLowerCase(Locale.ROOT), D.str(b, "term").toLowerCase(Locale.ROOT)));
            render("fn_glossary.html", "glossary.html", "glossary", "Glossary, assumptions and open questions", D.of("terms", terms,
                    "tool_terms", TechReport.GLOSSARY, "assumptions", D.get(asm, "assumptions", new ArrayList<>()),
                    "questions", D.get(asm, "open_questions", new ArrayList<>()), "gaps", gaps,
                    "missing_functional", missing("functional")));
        }
    }
}
