package com.legacyaudit.reports;

import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.json.PyJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Application servers, web servers and runtimes, one row per product, for the reports.
 *
 * <p>The rows come from the tech-stack items the scan found (categories {@code app-server},
 * {@code web-server}, and the Java and Node.js runtimes, decision 131): every version seen, end of
 * life, the components whose files name the product, the files themselves and the default target
 * from {@code rules/target-mapping.yaml} for the chosen target (none when there is no target),
 * replaced by the AI's target when the target architecture stage names one for the product
 * ({@code platform_changes}, marked "(AI)"). Nothing is counted here; it only groups the scan's
 * items.
 */
public final class Servers {

    private Servers() {}

    static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("app-server", "Application server");
        KINDS.put("web-server", "Web server");
        KINDS.put("runtime", "Runtime");
    }

    static final List<String> ORDER = List.copyOf(KINDS.keySet());
    private static final Map<String, String> TARGET_ID = Map.of("app-server", "app-server", "web-server", "web-server");
    private static final Map<String, String> RUNTIME_ID = Map.of("Node.js", "runtime-node", "Java", "runtime-java");
    static final int MAX_FILES = 5;

    private static List<Long> versionKey(String v) {
        List<Long> out = new ArrayList<>();
        for (String part : v.replace("-", ".").split("\\.", -1)) {
            out.add(part.matches("\\d+") ? Long.parseLong(part) : 0L);
        }
        return out;
    }

    private static int compareVersions(String a, String b) {
        List<Long> x = versionKey(a);
        List<Long> y = versionKey(b);
        for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
            int c = Long.compare(x.get(i), y.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.size(), y.size());
    }

    private static List<String> sortedVersions(List<Map<String, Object>> items) {
        Set<String> set = new TreeSet<>(PyJson::compareCodePoints);
        for (Map<String, Object> t : items) {
            if (D.t(t, "version")) {
                set.add(D.str(t, "version"));
            }
        }
        List<String> out = new ArrayList<>(set);
        out.sort(Servers::compareVersions);
        return out;
    }

    /** The AI's platform changes by tech id and by product name (lower case). */
    public static Map<String, Map<String, Object>> aiChanges(Map<String, Object> enrichment) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, Object> ta = D.map(enrichment, "target_architecture");
        for (Map<String, Object> ch : D.maps(ta, "platform_changes")) {
            List<String> keys = new ArrayList<>(D.strings(ch, "tech_ids"));
            keys.add(Py.str(D.or(D.get(ch, "name"), "")).toLowerCase(Locale.ROOT));
            for (String key : keys) {
                if (!key.isEmpty()) {
                    out.putIfAbsent(key, ch);
                }
            }
        }
        return out;
    }

    /** One row per server or runtime product, application servers first. */
    public static List<Map<String, Object>> serverRows(RunData rd, Map<String, Object> enrichment) {
        Map<Object, Object> targets = new LinkedHashMap<>();
        for (Map<String, Object> m : rd.targetMapping) {
            targets.put(D.get(m, "id"), D.get(m, "target", ""));
        }
        Map<String, Map<String, Object>> changes = aiChanges(enrichment);
        Map<List<String>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> t : rd.items("tech_stack")) {
            String cat = D.s(t, "category");
            if (KINDS.containsKey(cat) && (!cat.equals("runtime") || RUNTIME_ID.containsKey(D.str(t, "name")))) {
                groups.computeIfAbsent(List.of(cat, D.str(t, "name")), k -> new ArrayList<>()).add(t);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        groups.forEach((key, items) -> {
            String cat = key.get(0);
            String name = key.get(1);
            List<String> versions = sortedVersions(items);
            List<String> files = new ArrayList<>();
            Map<String, Object> lines = new LinkedHashMap<>();
            for (Map<String, Object> t : items) {
                for (Map<String, Object> ev : D.maps(t, "evidence")) {
                    String f = D.sOrNull(ev, "file");
                    if (f != null && !lines.containsKey(f)) {
                        files.add(f);
                        lines.put(f, D.get(ev, "line"));
                    }
                }
            }
            Set<String> compSet = new TreeSet<>(PyJson::compareCodePoints);
            for (String f : files) {
                String c = rd.fileComponent.get(f);
                if (c != null && !c.isEmpty()) {
                    compSet.add(c);
                }
            }
            List<Map<String, Object>> eolItems = new ArrayList<>();
            items.forEach(t -> {
                if (D.t(t, "eol")) {
                    eolItems.add(t);
                }
            });
            String eol = "";
            if (!eolItems.isEmpty()) {
                Map<String, Object> first = eolItems.get(0);
                if (versions.size() > 1) {
                    Set<String> old = new TreeSet<>(PyJson::compareCodePoints);
                    eolItems.forEach(t -> old.add(D.s(t, "version")));
                    List<String> o = new ArrayList<>(old);
                    o.sort(Servers::compareVersions);
                    eol = "yes: " + String.join(", ", o);
                } else {
                    eol = "yes" + (D.t(first, "eol_date") ? " (since " + D.str(first, "eol_date") + ")" : "");
                }
            } else if (items.stream().anyMatch(t -> Boolean.FALSE.equals(D.get(t, "eol")))) {
                eol = "no";
            }
            String replacement = "";
            for (Map<String, Object> t : eolItems) {
                if (D.t(t, "replacement")) {
                    replacement = D.str(t, "replacement");
                    break;
                }
            }
            String idKey = TARGET_ID.getOrDefault(cat, "");
            if (idKey.isEmpty()) {
                idKey = RUNTIME_ID.getOrDefault(name, "");
            }
            Object rule = targets.getOrDefault(idKey, "");
            Map<String, Object> ai = null;
            for (Map<String, Object> t : items) {
                Object id = D.get(t, "id");
                if (id instanceof String s && changes.containsKey(s)) {
                    ai = changes.get(s);
                    break;
                }
            }
            if (ai == null) {
                ai = changes.get(name.toLowerCase(Locale.ROOT));
            }
            long usages = 0;
            for (Map<String, Object> t : items) {
                usages += D.t(t, "usages") ? D.i(t, "usages") : 1;
            }
            rows.add(D.of("name", name, "category", cat, "kind", KINDS.get(cat), "versions", versions,
                    "version", String.join(", ", versions), "eol", eol, "replacement", replacement,
                    "components", new ArrayList<>(compSet), "files", new ArrayList<>(files.subList(0, Math.min(MAX_FILES, files.size()))),
                    "more_files", (long) Math.max(0, files.size() - MAX_FILES), "lines", lines, "usages", usages,
                    "target", ai != null ? Py.str(ai.get("target")) + " (AI)" : rule, "target_rule", rule,
                    "today", ai != null && D.t(ai, "today") ? D.str(ai, "today") + " (AI)" : ""));
        });
        rows.sort((a, b) -> {
            int c = Integer.compare(ORDER.indexOf(D.str(a, "category")), ORDER.indexOf(D.str(b, "category")));
            return c != 0 ? c : PyJson.compareCodePoints(D.str(a, "name").toLowerCase(Locale.ROOT), D.str(b, "name").toLowerCase(Locale.ROOT));
        });
        return rows;
    }
}
