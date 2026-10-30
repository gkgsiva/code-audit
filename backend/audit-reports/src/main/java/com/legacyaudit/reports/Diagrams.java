package com.legacyaudit.reports;

import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.json.PyJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mermaid diagrams generated from the facts (code edges, integrations, batch jobs).
 *
 * <p>Every node and arrow comes from a fact; labels are escaped for Mermaid. Large estates are
 * capped ({@link #MAX_NODES}) with a note, since a diagram past that size is unreadable; the
 * catalogues hold the full lists.
 */
public final class Diagrams {

    private Diagrams() {}

    public static final int MAX_NODES = 60;
    static final Pattern CONDITION = Pattern.compile(
            "\\b(s|success|f|failure|d|done|n|notrunning|e|exitcode)\\s*\\(\\s*([\\w.#-]+)", Pattern.CASE_INSENSITIVE);
    static final Map<String, String> COND_LABEL = Map.of(
            "s", "success", "f", "failure", "d", "done", "n", "not running", "e", "exit code");
    static final Set<String> RUN_EDGES = Set.of("invokes", "runs", "triggers", "calls");

    /** A Mermaid diagram with its title, a note and how many nodes it draws. */
    public record Diagram(String title, String source, String note, int nodes) {}

    /** A quoted Mermaid label (quotes and markup characters escaped as entities). */
    public static String label(Object text) {
        String out = Py.str(text)
                .replace("&", "#amp;")
                .replace("\"", "#quot;")
                .replace("<", "#lt;")
                .replace(">", "#gt;")
                .replace("@", "#64;") // Mermaid reads user@host as a markdown link
                .replace("://", "#58;//"); // ...and http://host as one
        return "\"" + out + "\"";
    }

    /** {@code (word, name)} pairs of a scheduler condition, as in {@code s(JOB_A) & f(JOB_B)}. */
    static List<String[]> conditions(String condition) {
        List<String[]> out = new ArrayList<>();
        Matcher m = CONDITION.matcher(condition == null ? "" : condition);
        while (m.find()) {
            String word = COND_LABEL.getOrDefault(m.group(1).substring(0, 1).toLowerCase(java.util.Locale.ROOT), m.group(1));
            out.add(new String[] {m.group(2), word});
        }
        return out;
    }

    static final class Ids {
        private final String prefix;
        private final Map<String, String> ids = new LinkedHashMap<>();

        Ids(String prefix) {
            this.prefix = prefix;
        }

        String id(String key) {
            return ids.computeIfAbsent(key, k -> prefix + ids.size());
        }
    }

    private static int cmp(String a, String b) {
        return PyJson.compareCodePoints(a, b);
    }

    /** Components and the code edges between them (calls, includes, uses, runs...). */
    public static Diagram componentGraph(RunData rd) {
        Map<List<String>, Integer> pairs = new LinkedHashMap<>();
        Map<List<String>, Set<String>> kinds = new LinkedHashMap<>();
        for (Map<String, Object> e : rd.items("dependencies")) {
            if (!Py.truthy(D.get(e, "resolved", true))) {
                continue;
            }
            String a = rd.componentOf(D.str(e, "source"));
            String b = rd.componentOf(D.str(e, "target"));
            if (a != null && b != null && !a.equals(b)) {
                List<String> k = List.of(a, b);
                pairs.merge(k, 1, Integer::sum);
                kinds.computeIfAbsent(k, x -> new TreeSet<>(Diagrams::cmp)).add(D.str(e, "kind"));
            }
        }
        Map<String, Map<String, Object>> comps = new LinkedHashMap<>();
        for (Map<String, Object> c : rd.items("components")) {
            comps.put(D.str(c, "name"), c);
        }
        List<String> ranked = new ArrayList<>(comps.keySet());
        ranked.sort((x, y) -> Double.compare(D.num(D.get(comps.get(y), "loc_code", 0)), D.num(D.get(comps.get(x), "loc_code", 0))));
        Set<String> keep = new TreeSet<>(Diagrams::cmp);
        keep.addAll(ranked.subList(0, Math.min(MAX_NODES, ranked.size())));
        Ids ids = new Ids("c");
        List<String> lines = new ArrayList<>(List.of("flowchart LR"));
        for (String name : keep) {
            Map<String, Object> c = comps.get(name);
            String text = label(name + " · " + D.str(c, "type") + " · " + Py.str(D.get(c, "loc_code", 0L)) + " LOC");
            lines.add("  " + ids.id(name) + "[" + text + "]");
        }
        List<List<String>> sortedPairs = new ArrayList<>(pairs.keySet());
        sortedPairs.sort((x, y) -> {
            int c = cmp(x.get(0), y.get(0));
            return c != 0 ? c : cmp(x.get(1), y.get(1));
        });
        for (List<String> p : sortedPairs) {
            String a = p.get(0);
            String b = p.get(1);
            if (keep.contains(a) && keep.contains(b)) {
                lines.add("  " + ids.id(a) + " -->|" + label(pairs.get(p) + " · " + String.join(", ", kinds.get(p))) + "| " + ids.id(b));
            }
        }
        String note = "";
        if (comps.size() > MAX_NODES) {
            note = "Showing the " + MAX_NODES + " largest of " + comps.size() + " components.";
        }
        if (pairs.isEmpty()) {
            note = (note.isEmpty() ? "" : note + " ") + "No code edges cross component boundaries.";
        }
        return new Diagram("Component dependencies", String.join("\n", lines), note, keep.size());
    }

    /** Components and the external systems they reach (databases, queues, hosts, files). */
    public static Diagram integrationMap(RunData rd) {
        List<Map<String, Object>> integs = new ArrayList<>(rd.items("integrations"));
        integs.sort((x, y) -> Long.compare(D.i(y, "usages"), D.i(x, "usages")));
        List<Map<String, Object>> shown = integs.subList(0, Math.min(MAX_NODES, integs.size()));
        Ids compIds = new Ids("c");
        Ids intIds = new Ids("i");
        List<String> lines = new ArrayList<>(List.of("flowchart LR"));
        Set<String> comps = new TreeSet<>(Diagrams::cmp);
        for (Map<String, Object> i : shown) {
            comps.addAll(D.strings(i, "components"));
        }
        for (String c : comps) {
            lines.add("  " + compIds.id(c) + "[" + label(c) + "]");
        }
        for (Map<String, Object> i : shown) {
            String iid = intIds.id(D.str(i, "id"));
            String text = label(D.str(i, "type") + ": " + D.str(i, "target"));
            String shape = "DB".equals(D.get(i, "type")) ? "[(" + text + ")]" : "([" + text + "])";
            lines.add("  " + iid + shape);
            String direction = D.s(i, "direction").isEmpty() ? "unknown" : D.s(i, "direction");
            for (String c : D.strings(i, "components")) {
                switch (direction) {
                    case "outbound" -> lines.add("  " + compIds.id(c) + " --> " + iid);
                    case "inbound" -> lines.add("  " + iid + " --> " + compIds.id(c));
                    case "both" -> lines.add("  " + compIds.id(c) + " <--> " + iid);
                    default -> lines.add("  " + compIds.id(c) + " -.- " + iid);
                }
            }
        }
        String note = integs.size() > MAX_NODES ? "Showing the " + MAX_NODES + " most used of " + integs.size() + " integrations." : "";
        if (integs.isEmpty()) {
            note = "No integrations found.";
        }
        return new Diagram("Integration map", String.join("\n", lines), note, comps.size() + shown.size());
    }

    /**
     * One flowchart per scheduler: jobs inside their boxes, dependencies with their condition, and
     * what each job runs (scripts, programs, Spring Batch jobs, steps).
     */
    public static List<Diagram> batchChains(RunData rd) {
        List<Map<String, Object>> jobs = rd.items("batch_jobs");
        Map<String, List<Map<String, Object>>> bySched = new TreeMap<>(Diagrams::cmp);
        for (Map<String, Object> j : jobs) {
            String s = D.s(j, "scheduler");
            bySched.computeIfAbsent(s.isEmpty() ? "unknown" : s, k -> new ArrayList<>()).add(j);
        }
        Map<String, List<String>> runs = new LinkedHashMap<>();
        for (Map<String, Object> e : rd.items("dependencies")) {
            String src = D.str(e, "source");
            if (RUN_EDGES.contains(D.str(e, "kind")) && (src.startsWith("batch_job:") || src.startsWith("file:"))) {
                runs.computeIfAbsent(src, k -> new ArrayList<>()).add(D.str(e, "target"));
            }
        }
        List<Diagram> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> en : bySched.entrySet()) {
            String sched = en.getKey();
            List<Map<String, Object>> all = en.getValue();
            List<Map<String, Object>> group = all.subList(0, Math.min(MAX_NODES, all.size()));
            Ids ids = new Ids("j");
            Set<String> names = new java.util.HashSet<>();
            for (Map<String, Object> j : group) {
                names.add(D.str(j, "name"));
            }
            List<String> lines = new ArrayList<>(List.of("flowchart TD"));
            Map<String, List<Map<String, Object>>> boxes = new TreeMap<>(Diagrams::cmp);
            for (Map<String, Object> j : group) {
                if (!"box".equals(D.get(j, "kind"))) {
                    boxes.computeIfAbsent(D.s(j, "box"), k -> new ArrayList<>()).add(j);
                }
            }
            for (Map.Entry<String, List<Map<String, Object>>> b : boxes.entrySet()) {
                String box = b.getKey();
                if (!box.isEmpty()) {
                    lines.add("  subgraph " + ids.id("box:" + box) + "[" + label("box " + box) + "]");
                }
                for (Map<String, Object> j : b.getValue()) {
                    String kind = D.s(j, "kind").isEmpty() ? "job" : D.s(j, "kind");
                    String text = label(!kind.equals("job") ? D.str(j, "name") + " (" + kind + ")" : D.str(j, "name"));
                    String shape = kind.equals("file-watcher") ? "{{" + text + "}}" : "[" + text + "]";
                    lines.add("    " + ids.id(D.str(j, "name")) + shape);
                }
                if (!box.isEmpty()) {
                    lines.add("  end");
                }
            }
            for (Map<String, Object> j : group) {
                String jn = D.str(j, "name");
                Map<String, String> conds = new LinkedHashMap<>();
                for (String[] c : conditions(D.s(j, "condition"))) {
                    conds.put(c[0], c[1]);
                }
                for (String pred : D.strings(j, "predecessors")) {
                    if (names.contains(pred)) {
                        String lbl = conds.get(pred);
                        String arrow = lbl != null && !lbl.isEmpty() ? "-->|" + label(lbl) + "|" : "-->";
                        lines.add("  " + ids.id(pred) + " " + arrow + " " + ids.id(jn));
                    }
                }
                for (String target : runTargets(rd, runs, D.str(j, "id"))) {
                    lines.add("  " + ids.id(jn) + " -.->|runs| " + ids.id("t:" + target) + "[" + label(target) + "]");
                }
                List<String> steps = D.strings(j, "steps");
                String prev = ids.id(jn);
                for (String step : steps.subList(0, Math.min(12, steps.size()))) {
                    String sid = ids.id("step:" + jn + ":" + step);
                    lines.add("  " + prev + " --> " + sid + "([" + label(step) + "])");
                    prev = sid;
                }
                if (steps.isEmpty()) {
                    List<String> progs = D.strings(j, "programs");
                    for (String prog : progs.subList(0, Math.min(6, progs.size()))) {
                        String text = label(D.last(prog, "."));
                        lines.add("  " + ids.id(jn) + " -.-> " + ids.id("p:" + prog) + "[" + text + "]");
                    }
                }
            }
            String note = all.size() > MAX_NODES ? "Showing " + MAX_NODES + " of " + all.size() + " jobs." : "";
            out.add(new Diagram("Batch chains: " + sched, String.join("\n", lines), note, group.size()));
        }
        return out;
    }

    /** What a job runs: scripts and programs, then (one hop on) the jobs those trigger. */
    private static List<String> runTargets(RunData rd, Map<String, List<String>> runs, String jobId) {
        List<String> out = new ArrayList<>();
        for (String t : runs.getOrDefault(jobId, List.of())) {
            if (t.startsWith("unresolved:")) {
                out.add(t.substring(t.indexOf(':') + 1) + " (missing)");
                continue;
            }
            if (t.startsWith("batch_job:")) {
                continue;
            }
            out.add(D.last(rd.label(t), "/"));
            for (String t2 : runs.getOrDefault(t, List.of())) {
                if (t2.startsWith("batch_job:")) {
                    out.add(rd.label(t2) + " (batch job)");
                }
            }
        }
        List<String> u = D.unique(out);
        return u.subList(0, Math.min(6, u.size()));
    }

    private static final List<String> LAYER_FILLS = List.of("#dbe9fb", "#ece3fb", "#dcf3dc", "#fde7d9", "#fff1cc", "#d8f1f0", "#fbe1ec");
    private static final List<String> LAYER_STROKES = List.of("#2a78d6", "#6250d6", "#0ca30c", "#eb6834", "#d69a00", "#13867f", "#e87ba4");

    /** The agent's layers (enrichment {@code architecture.layers}) as coloured bands, top down. */
    public static Diagram layered(Map<String, Object> architecture) {
        List<Map<String, Object>> layers = D.maps(architecture, "layers");
        if (layers.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(List.of("flowchart TB"));
        String prev = null;
        for (int i = 0; i < layers.size(); i++) {
            Map<String, Object> layer = layers.get(i);
            String lid = "L" + i;
            lines.add("  subgraph " + lid + "[" + label(layer.get("name")) + "]");
            lines.add("    direction LR");
            List<Object> comps = D.list(layer, "components");
            if (comps.isEmpty()) {
                comps = List.of("(none)");
            }
            for (int k = 0; k < comps.size(); k++) {
                lines.add("    " + lid + "c" + k + "[" + label(comps.get(k)) + "]");
            }
            lines.add("  end");
            lines.add("  style " + lid + " fill:" + LAYER_FILLS.get(i % 7) + ",stroke:" + LAYER_STROKES.get(i % 7) + ",color:#0b0b0b");
            if (prev != null) {
                lines.add("  " + prev + " --> " + lid);
            }
            prev = lid;
        }
        return new Diagram("Layers (interpreted by the Copilot agent)", String.join("\n", lines),
                "Layers and their components come from enrichment/architecture.json.", layers.size());
    }

    /**
     * Legacy components (scan) on the left, target components (enrichment) on the right, an arrow
     * for each 'replaces'.
     */
    public static Diagram currentToTarget(RunData rd, Map<String, Object> target) {
        List<Map<String, Object>> comps = D.maps(target, "components");
        if (comps.isEmpty()) {
            return null;
        }
        Set<String> legacy = new TreeSet<>(Diagrams::cmp);
        for (Map<String, Object> c : comps) {
            legacy.addAll(D.strings(c, "replaces"));
        }
        List<String> lines = new ArrayList<>(List.of("flowchart LR", "  subgraph NOW[Today]"));
        Ids ids = new Ids("o");
        for (String name : legacy) {
            lines.add("    " + ids.id(name) + "[" + label(name) + "]");
        }
        lines.add("  end");
        lines.add("  subgraph NEXT[Target on Google Cloud]");
        Ids tids = new Ids("t");
        for (Map<String, Object> c : comps) {
            String text = label(D.str(c, "name") + " · " + D.str(c, "technology") + " · " + D.str(c, "platform"));
            lines.add("    " + tids.id(D.str(c, "name")) + "([" + text + "])");
        }
        lines.add("  end");
        for (Map<String, Object> c : comps) {
            for (String r : D.strings(c, "replaces")) {
                lines.add("  " + ids.id(r) + " ==> " + tids.id(D.str(c, "name")));
            }
        }
        lines.add("  style NOW fill:#f0efec,stroke:#898781,color:#0b0b0b");
        lines.add("  style NEXT fill:#dcf3dc,stroke:#0ca30c,color:#0b0b0b");
        return new Diagram("Today and target (interpreted by the Copilot agent)", String.join("\n", lines),
                "Target components and what they replace come from enrichment/target_architecture.json.", legacy.size() + comps.size());
    }
}
