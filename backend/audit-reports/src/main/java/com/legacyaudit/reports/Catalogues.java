package com.legacyaudit.reports;

import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.json.PyJson;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Catalogue tables: which columns each entity shows, and its rows as plain lists.
 *
 * <p>A catalogue is written next to the pages as {@code data/<name>.js}
 * ({@code LA_DATA["<name>"] = {...}}) and drawn by {@code report.js} with search, per-column
 * filters, sorting and paging. A script file and not JSON, because browsers refuse {@code fetch()}
 * on pages opened from disk ({@code file://}).
 *
 * <p>Column types: {@code text}, {@code num}, {@code code} (monospace), {@code badge}
 * (grade/severity chip), {@code list} (comma-joined), {@code link} ({@code {text, href}} to another
 * report page) and {@code evidence} ({@code {text, href, vscode}} to the source file).
 * {@code filter: true} adds a value filter above the column.
 */
public final class Catalogues {

    private Catalogues() {}

    public record Column(String key, String label, String type, boolean filter) {
        Column(String key, String label) {
            this(key, label, "text", false);
        }

        Column(String key, String label, String type) {
            this(key, label, type, false);
        }

        Map<String, Object> spec() {
            return D.of("key", key, "label", label, "type", type, "filter", filter);
        }
    }

    private static Column col(String key, String label, boolean filter) {
        return new Column(key, label, "text", filter);
    }

    public record Catalogue(String name, String title, String intro, List<Column> columns, List<List<Object>> rows) {

        public Map<String, Object> payload() {
            List<Object> cols = new ArrayList<>();
            columns.forEach(c -> cols.add(c.spec()));
            return D.of("columns", cols, "rows", rows);
        }

        /** Remove a column (and its cell in every row), e.g. the PostgreSQL grade when there is no target. */
        public void drop(String key) {
            for (int i = columns.size() - 1; i >= 0; i--) {
                if (columns.get(i).key().equals(key)) {
                    columns.remove(i);
                    for (List<Object> row : rows) {
                        row.remove(i);
                    }
                }
            }
        }

        public Catalogue withIntro(String text) {
            return new Catalogue(name, title, text, columns, rows);
        }
    }

    private static List<Object> row(Object... cells) {
        return new ArrayList<>(Arrays.asList(cells));
    }

    static Object comp(Object name) {
        if (!Py.truthy(name)) {
            return "";
        }
        return D.of("text", Py.str(name), "href", "components/" + RunData.slug(Py.str(name)) + ".html");
    }

    static String join(Object values) {
        if (!Py.truthy(values)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (values instanceof Map<?, ?> m) {
            m.forEach((k, v) -> parts.add(Py.str(k) + ": " + Py.str(v)));
        } else {
            for (Object v : (List<?>) values) {
                parts.add(Py.str(v));
            }
        }
        return String.join(", ", parts);
    }

    public static String libraryName(Map<String, Object> lib) {
        return D.t(lib, "group") ? D.str(lib, "group") + ":" + D.str(lib, "artifact") : D.str(lib, "artifact");
    }

    /** The Copilot interface story (stage 12) when there is one, else the scan's sentence. */
    static String aiOr(Object description, Map<String, Object> story) {
        if (story != null && D.t(story, "purpose")) {
            String partner = D.t(story, "partner") ? D.str(story, "partner") + ": " : "";
            return partner + D.str(story, "purpose") + " (AI)";
        }
        return Py.truthy(description) ? Py.str(description) : "";
    }

    /**
     * (what it is for, suggested migration): the Copilot story (stage 12) marked AI, else the
     * scan's sentence and nothing.
     */
    public static List<String> wsText(Map<String, Object> ws, Map<String, Object> story) {
        if (story != null && D.t(story, "purpose")) {
            String partner = D.t(story, "partner") ? D.str(story, "partner") + ": " : "";
            String mig = D.t(story, "migration") ? D.str(story, "migration") + " (AI)" : "";
            return List.of(partner + D.str(story, "purpose") + " (AI)", mig);
        }
        return List.of(D.s(ws, "description"), "");
    }

    public static String wsLabel(Map<String, Object> ws) {
        return D.str(ws, "style") + " " + ("offered".equals(D.get(ws, "side")) ? "offered" : "called");
    }

    public static String recommended(Map<String, Object> lib) {
        Map<String, Object> rec = D.map(lib, "recommendation");
        Object target = D.or(D.or(D.get(rec, "target"), D.get(lib, "recommended_version")), "");
        return Py.truthy(target) && D.t(rec, "verify") ? Py.str(target) + " (verify)" : Py.str(target);
    }

    private static Object first(Object v, Object dflt) {
        return v == null ? dflt : v;
    }

    /**
     * Every catalogue of the run; {@code root} is the report folder (evidence links are relative).
     * {@code stories} are the Copilot interface stories by integration id and {@code wsStories} its
     * web service stories by web service id, when the stage exists.
     */
    public static Map<String, Catalogue> build(RunData rd, Path root, Map<String, Map<String, Object>> stories,
            Map<String, Map<String, Object>> wsStories) {
        java.util.function.Function<Map<String, Object>, Object> ev = item -> {
            List<Object> fl = rd.firstEvidence(item);
            return rd.evidenceLinks(root, fl.get(0), fl.get(1));
        };
        Map<String, Map<String, Object>> st = stories == null ? Map.of() : stories;
        Map<String, Map<String, Object>> ws = wsStories == null ? Map.of() : wsStories;
        List<Catalogue> cats = new ArrayList<>();

        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> s : rd.items("services")) {
            rows.add(row(s.get("name"), s.get("kind"), comp(D.get(s, "component")), D.s(s, "url"), D.s(s, "handler"),
                    D.s(s, "complexity"), join(D.get(s, "tables")), join(D.get(s, "external_calls")), D.get(s, "fan_in", 0L),
                    D.get(s, "fan_out", 0L), ev.apply(s)));
        }
        cats.add(new Catalogue("services", "Services", "Independently invokable operations (counting rule in the appendix).",
                new ArrayList<>(List.of(new Column("name", "Name", "code"), col("kind", "Kind", true),
                        new Column("component", "Component", "link", true), new Column("url", "URL", "code"),
                        new Column("handler", "Handler", "code"), new Column("complexity", "Complexity", "badge", true),
                        new Column("tables", "Tables", "list"), new Column("calls", "External calls", "list"),
                        new Column("fan_in", "Fan-in", "num"), new Column("fan_out", "Fan-out", "num"),
                        new Column("evidence", "Evidence", "evidence"))),
                rows));

        rows = new ArrayList<>();
        for (Map<String, Object> s : rd.items("screens")) {
            rows.add(row(s.get("name"), D.s(s, "technology"), comp(D.get(s, "component")), D.t(s, "partial") ? "yes" : "no",
                    D.get(s, "fields_count", 0L), D.s(s, "complexity"), (long) D.list(s, "targeted_by").size(), ev.apply(s)));
        }
        cats.add(new Catalogue("screens", "Screens",
                "Rendered pages and maps; partials (included fragments) are listed but not counted.",
                new ArrayList<>(List.of(new Column("name", "Screen", "code"), col("technology", "Technology", true),
                        new Column("component", "Component", "link", true), col("partial", "Partial", true),
                        new Column("fields", "Fields", "num"), new Column("complexity", "Complexity", "badge", true),
                        new Column("targeted", "Reached from", "num"), new Column("evidence", "Evidence", "evidence"))),
                rows));

        rows = new ArrayList<>();
        for (Map<String, Object> q : rd.items("sql_statements")) {
            rows.add(row(ev.apply(q), q.get("type"), q.get("source"), comp(D.get(q, "component")), D.s(q, "pg_grade"),
                    D.t(q, "dynamic") || "DYNAMIC".equals(q.get("type")) ? "yes" : "no",
                    Py.str(D.get(q, "value_holes", 0L)) + "/" + Py.str(D.get(q, "structural_holes", 0L)),
                    join(D.get(q, "tables")), D.get(q, "text", ""), D.s(q, "reason")));
        }
        cats.add(new Catalogue("sql", "SQL statements",
                "Every statement found, with its PostgreSQL grade. ? marks a part only known at run "
                        + "time (value holes are an injection risk, structural holes make the statement manual).",
                new ArrayList<>(List.of(new Column("evidence", "Where", "evidence"), col("type", "Type", true),
                        col("source", "Source", true), new Column("component", "Component", "link", true),
                        new Column("grade", "PG grade", "badge", true), col("dynamic", "Dynamic", true),
                        new Column("holes", "Holes (value/structural)", "text"), new Column("tables", "Tables", "list"),
                        new Column("text", "Statement", "code"), new Column("reason", "Why", "text"))),
                rows));

        rows = new ArrayList<>();
        for (Map<String, Object> o : rd.items("db_objects")) {
            StringBuilder crud = new StringBuilder();
            for (char k : "CRUD".toCharArray()) {
                if (Py.truthy(D.get(D.map(o, "crud"), String.valueOf(k)))) {
                    crud.append(k);
                }
            }
            rows.add(row(o.get("name"), o.get("object_type"), crud.toString(), (long) D.list(o, "referenced_by").size(),
                    D.s(o, "pg_grade"), ev.apply(o)));
        }
        cats.add(new Catalogue("db-objects", "Database objects",
                "Tables, views and stored code referenced by SQL or defined in DDL; C/R/U/D = "
                        + "statements that create, read, update or delete.",
                new ArrayList<>(List.of(new Column("name", "Object", "code"), col("type", "Type", true),
                        new Column("crud", "CRUD", "text"), new Column("refs", "Referenced by", "num"),
                        new Column("grade", "PG grade", "badge", true), new Column("evidence", "Evidence", "evidence"))),
                rows));

        cats.add(crudMatrix(rd));

        rows = new ArrayList<>();
        for (Map<String, Object> i : rd.items("integrations")) {
            rows.add(row(i.get("type"), D.s(i, "purpose"), i.get("target"), join(D.get(i, "endpoints")),
                    aiOr(D.get(i, "description"), st.get(D.str(i, "id"))), D.s(i, "direction"), D.s(i, "protocol"),
                    D.get(i, "usages", 0L), join(D.get(i, "components")), join(D.get(i, "producers")),
                    join(D.get(i, "consumers")), ev.apply(i)));
        }
        cats.add(new Catalogue("integrations", "Integrations",
                "One row per external service: the endpoints called on one host (REST, SOAP or "
                        + "HTTP), the queues and channels of one queue manager and the files of one server or "
                        + "folder are one row, listed under Endpoints. A target both read and written shows "
                        + "direction both; usages counts the code places that use it. Calls to an identity "
                        + "provider (Okta, Entra ID, ...) are 'login / authentication'. The description is "
                        + "the scan's; once the Copilot analysis has run, its business purpose (AI) replaces "
                        + "it.",
                new ArrayList<>(List.of(col("type", "Type", true), col("purpose", "Purpose", true),
                        new Column("target", "Target", "code"), new Column("endpoints", "Endpoints", "list"),
                        new Column("description", "Description"), col("direction", "Direction", true),
                        col("protocol", "Protocol", true), new Column("usages", "Usages", "num"),
                        new Column("components", "Components", "list"), new Column("producers", "Producers", "list"),
                        new Column("consumers", "Consumers", "list"), new Column("evidence", "Evidence", "evidence"))),
                rows));

        List<Map<String, Object>> web = rd.items("web_services");
        rows = new ArrayList<>();
        for (Map<String, Object> w : web) {
            List<String> text = wsText(w, ws.get(D.str(w, "id")));
            rows.add(row(w.get("side"), w.get("style"), w.get("name"), D.s(w, "url"), (long) D.list(w, "operations").size(),
                    join(D.get(w, "frameworks")), comp(D.get(w, "component")), text.get(0), text.get(1), D.s(w, "wsdl"),
                    (long) D.list(w, "callers").size(), ev.apply(w)));
        }
        cats.add(new Catalogue("web-services", "Web services",
                "REST and SOAP web services the application offers (others call it) and the ones it "
                        + "calls, one row per service with its operations. Descriptions marked (AI) come from "
                        + "the Copilot analysis.",
                new ArrayList<>(List.of(col("side", "Offered / called", true), col("style", "REST / SOAP", true),
                        new Column("name", "Web service", "code"), new Column("url", "Address", "code"),
                        new Column("operations", "Operations", "num"), new Column("frameworks", "Found through", "list"),
                        new Column("component", "Component", "link", true), new Column("description", "What it is for", "text"),
                        new Column("migration", "Suggested migration", "text"), new Column("wsdl", "WSDL", "code"),
                        new Column("callers", "Callers", "num"), new Column("evidence", "Evidence", "evidence"))),
                rows));
        rows = new ArrayList<>();
        for (Map<String, Object> w : web) {
            for (Map<String, Object> o : D.maps(w, "operations")) {
                rows.add(row(w.get("side"), w.get("name"), o.get("name"), D.s(o, "method"), D.s(o, "path"), D.s(o, "handler"),
                        rd.evidenceLinks(root, D.get(o, "file"), D.get(o, "line"))));
            }
        }
        cats.add(new Catalogue("web-operations", "Web service operations",
                "Every operation of every web service: HTTP method and path (REST) or operation "
                        + "name (SOAP), with the code that implements or calls it.",
                new ArrayList<>(List.of(col("side", "Offered / called", true), new Column("service", "Web service", "code", true),
                        new Column("operation", "Operation", "code"), col("method", "Method", true),
                        new Column("path", "Path", "code"), new Column("handler", "Code", "code"),
                        new Column("evidence", "Where", "evidence"))),
                rows));

        rows = new ArrayList<>();
        for (Map<String, Object> j : rd.items("batch_jobs")) {
            rows.add(row(j.get("name"), D.s(j, "scheduler"), D.s(j, "kind"), D.s(j, "box"), D.s(j, "schedule"),
                    D.s(j, "condition"), D.t(j, "command") ? D.str(j, "command") : join(D.get(j, "programs")), D.s(j, "machine"),
                    comp(D.get(j, "component")), D.s(j, "complexity"), ev.apply(j)));
        }
        cats.add(new Catalogue("batch-jobs", "Scheduler job details",
                "Every job of the scheduler definitions provided (Autosys, JCL, cron, Spring Batch), "
                        + "one row per job; boxes group jobs and are not counted as jobs.",
                new ArrayList<>(List.of(new Column("name", "Job", "code"), col("scheduler", "Scheduler", true),
                        col("kind", "Kind", true), col("box", "Box", true), new Column("schedule", "Schedule"),
                        new Column("condition", "Condition", "code"), new Column("command", "Command / programs", "code"),
                        new Column("machine", "Machine"), new Column("component", "Component", "link", true),
                        new Column("complexity", "Complexity", "badge", true), new Column("evidence", "Evidence", "evidence"))),
                rows));

        rows = new ArrayList<>();
        for (Map<String, Object> lib : rd.items("libraries")) {
            Map<String, Object> rec = D.map(lib, "recommendation");
            rows.add(row(libraryName(lib), D.s(lib, "version"), D.s(lib, "scope"), D.t(lib, "declared") ? "yes" : "no",
                    D.t(lib, "license") ? D.str(lib, "license") : "unknown",
                    D.t(lib, "eol_date") ? D.str(lib, "eol_date") : D.t(lib, "eol") ? "yes" : "no",
                    (long) D.list(lib, "cves").size(), D.s(lib, "max_severity"), D.get(rec, "action", ""), recommended(lib),
                    D.get(rec, "reason", ""), ev.apply(lib)));
        }
        cats.add(new Catalogue("libraries", "Libraries",
                "Declared and undeclared libraries with known vulnerabilities, end of life and the recommended action.",
                new ArrayList<>(List.of(new Column("name", "Library", "code"), new Column("version", "Version", "code"),
                        col("scope", "Scope", true), col("declared", "Declared", true), col("license", "Licence", true),
                        col("eol", "End of life", true), new Column("cves", "CVEs", "num"),
                        new Column("severity", "Max severity", "badge", true), col("action", "Action", true),
                        new Column("target", "Recommended", "code"), new Column("reason", "Reason"),
                        new Column("evidence", "Evidence", "evidence"))),
                rows));

        rows = new ArrayList<>();
        for (Map<String, Object> f : rd.items("findings")) {
            rows.add(row(f.get("severity"), f.get("rule_id"), D.s(f, "category"),
                    comp(rd.fileComponent.get(D.s(f, "file"))), D.s(f, "message"), D.s(f, "remediation"), ev.apply(f)));
        }
        cats.add(new Catalogue("findings", "Findings", "Rule findings from the risk, PostgreSQL and dependency packs.",
                new ArrayList<>(List.of(new Column("severity", "Severity", "badge", true), col("rule", "Rule", true),
                        col("category", "Category", true), new Column("component", "Component", "link", true),
                        new Column("message", "Finding"), new Column("remediation", "Remediation"),
                        new Column("evidence", "Evidence", "evidence"))),
                rows));

        Map<String, Catalogue> out = new LinkedHashMap<>();
        cats.forEach(c -> out.put(c.name(), c));
        return out;
    }

    /** Tables by components: which component creates, reads, updates or deletes each table. */
    private static Catalogue crudMatrix(RunData rd) {
        Set<String> names = new TreeSet<>(PyJson::compareCodePoints);
        for (Map<String, Object> c : rd.items("components")) {
            names.add(D.str(c, "name"));
        }
        List<String> components = new ArrayList<>(names);
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> obj : rd.items("db_objects")) {
            Object type = D.get(obj, "object_type");
            if (!"table".equals(type) && !"view".equals(type)) {
                continue;
            }
            Map<String, Set<String>> cells = new LinkedHashMap<>();
            components.forEach(c -> cells.put(c, new LinkedHashSet<>()));
            for (Map.Entry<String, Object> e : D.map(obj, "crud").entrySet()) {
                for (Object sid : e.getValue() instanceof List<?> l ? l : List.of()) {
                    String comp = rd.componentOf(Py.str(sid));
                    if (comp != null && cells.containsKey(comp)) {
                        cells.get(comp).add(e.getKey());
                    }
                }
            }
            List<Object> row = row(obj.get("name"));
            for (String c : components) {
                StringBuilder sb = new StringBuilder();
                for (char k : "CRUD".toCharArray()) {
                    if (cells.get(c).contains(String.valueOf(k))) {
                        sb.append(k);
                    }
                }
                row.add(sb.toString());
            }
            rows.add(row);
        }
        List<Column> cols = new ArrayList<>(List.of(new Column("name", "Table", "code")));
        for (int i = 0; i < components.size(); i++) {
            cols.add(new Column("c" + i, components.get(i), "text", true));
        }
        return new Catalogue("crud", "CRUD matrix",
                "Which component creates (C), reads (R), updates (U) or deletes (D) each table, from the "
                        + "SQL statements found in its code.",
                cols, rows);
    }

    /**
     * The consolidated batch list ({@link Batches}): one row per batch, scheduled or not, with what
     * it does and when it runs, and where each came from.
     */
    public static Catalogue batches(RunData rd, Path root, List<Map<String, Object>> batchRows) {
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : batchRows) {
            Object whenEv = D.t(r, "when_file") ? rd.evidenceLinks(root, r.get("when_file"), D.get(r, "when_line")) : "";
            List<?> ev = (List<?>) r.get("evidence");
            rows.add(row(D.of("text", r.get("name"), "href", "flows/" + D.str(r, "slug") + ".html"), r.get("kind"),
                    comp(D.get(r, "component")), r.get("what"), r.get("what_from"), r.get("when"), r.get("when_from"),
                    r.get("scheduler"), r.get("steps"), whenEv, Py.truthy(ev.get(0)) ? rd.evidenceLinks(root, ev.get(0), ev.get(1)) : ""));
        }
        return new Catalogue("batches", "All batches",
                "Every batch once: scheduled boxes and jobs, and the batch components, programs and "
                        + "listeners no scheduler definition starts. 'What it does' comes from the Copilot "
                        + "analysis when present, else the scheduler's description, else the code's own header "
                        + "comment, else the scan; 'When it runs' from the scheduler, else the Copilot analysis, "
                        + "else what the code states (cron expressions, @Scheduled, comments).",
                new ArrayList<>(List.of(new Column("name", "Batch", "link"), col("kind", "Kind", true),
                        new Column("component", "Component", "link", true), new Column("what", "What it does"),
                        col("what_from", "Described by", true), new Column("when", "When it runs"),
                        col("when_from", "Schedule from", true), col("scheduler", "Scheduler", true),
                        new Column("steps", "Steps", "num"), new Column("when_evidence", "Schedule evidence", "evidence"),
                        new Column("evidence", "Evidence", "evidence"))),
                rows);
    }
}
