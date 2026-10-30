package com.legacyaudit.engine.linker;

import com.legacyaudit.engine.components.Kinds;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Component;
import com.legacyaudit.model.facts.ComponentType;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.FileRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Component types and roles from what the linker found in each component (decision 115): a
 * component can be web <em>and</em> batch (a Struts module with its nightly ksh), batch with Java,
 * ksh and properties, or only configuration. Overridden types are kept.
 */
public final class Classify {

    static final Set<String> WEB_SERVICE_KINDS = Set.of(
            "struts-action", "spring-handler", "servlet", "soap-operation", "rest-operation",
            "ejb-method", "webflow", "remoting", "aspnet-mvc");
    static final Set<String> SPA_ENTRY_KINDS = Set.of("angular-app", "angularjs-app");
    // Desktop applications (decisions 134-137): VB6 forms, Windows Forms, WPF, Java Swing/AWT/JavaFX.
    static final Set<String> DESKTOP_TECH = Set.of("vb6", "winforms", "wpf", "swing", "awt", "javafx");
    static final Set<String> DESKTOP_ENTRY_KINDS = Set.of("desktop-app");
    static final List<String> SCRIPT_SUFFIXES = List.of(".sh", ".ksh", ".bash", ".csh", ".zsh", ".pl", ".py", ".bat", ".cmd");

    private Classify() {}

    /**
     * Per component, the shell scripts that do batch work: they sit in a batch-like folder (batch,
     * jobs, cron, ...), run SQL, or start a program, a Java class or a SQL file. A script that only
     * calls other scripts, copies files or deploys is a utility, not batch work.
     */
    public static Map<String, Set<String>> batchScripts(Map<String, List<Doc>> entities, List<FileRecord> records) {
        Map<String, FileRecord> scripts = new LinkedHashMap<>();
        for (FileRecord r : records) {
            if (r.category == Category.SCRIPT && r.component != null && !r.component.isEmpty()) {
                scripts.put(r.path, r);
            }
        }
        Map<String, String> nodePath = new HashMap<>();
        for (FileRecord r : scripts.values()) {
            nodePath.put(r.id, r.path);
            nodePath.put("file:" + r.path, r.path);
        }
        Map<String, FileRecord> byId = new HashMap<>();
        for (FileRecord r : records) {
            byId.put(r.id, r);
        }
        Map<String, Set<String>> out = new HashMap<>();
        java.util.function.Consumer<String> mark = path -> out.computeIfAbsent(scripts.get(path).component, k -> new HashSet<>()).add(path);
        for (String path : scripts.keySet()) {
            if (Kinds.inBatchFolder(path) && !Kinds.inSharedFolder(path)) {
                mark.accept(path);
            }
        }
        for (Doc st : entities.getOrDefault("sql_statements", List.of())) {
            if (scripts.containsKey(st.str("file"))) {
                mark.accept(st.str("file"));
            }
        }
        for (Doc e : entities.getOrDefault("dependencies", List.of())) {
            String kind = e.str("kind");
            if (!"invokes".equals(kind) && !"runs".equals(kind) && !"triggers".equals(kind)) {
                continue;
            }
            String src = nodePath.get(e.str("source"));
            if (src == null) {
                continue;
            }
            FileRecord target = byId.get(e.str("target"));
            if (target != null && target.category == Category.SCRIPT) {
                continue;
            }
            if (target == null) {
                String t = e.str("target").toLowerCase(Locale.ROOT);
                if (SCRIPT_SUFFIXES.stream().anyMatch(t::endsWith)) {
                    continue;
                }
            }
            mark.accept(src);
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isEmpty();
    }

    private static boolean meets(Set<String> a, Set<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }

    /** Refine each component's type from what was found in it and list its roles. */
    public static void components(List<Component> components, Map<String, List<Doc>> entities, List<FileRecord> records, Set<String> fixed) {
        Map<String, Set<String>> services = new HashMap<>();
        for (Doc s : entities.getOrDefault("services", List.of())) {
            if (!blank(s.str("component"))) {
                services.computeIfAbsent(s.str("component"), k -> new HashSet<>()).add(s.str("kind"));
            }
        }
        Map<String, Set<String>> screens = new HashMap<>();
        Map<String, Set<String>> spa = new HashMap<>(); // SPA templates, partial or not
        for (Doc sc : entities.getOrDefault("screens", List.of())) {
            String comp = sc.str("component");
            if (blank(comp)) {
                continue;
            }
            if (!sc.bool("partial")) {
                screens.computeIfAbsent(comp, k -> new HashSet<>()).add(sc.str("technology"));
            }
            spa.computeIfAbsent(comp, k -> new HashSet<>()).add(sc.str("technology"));
        }
        Set<String> jobs = new HashSet<>();
        for (Doc j : entities.getOrDefault("batch_jobs", List.of())) {
            if (!blank(j.str("component"))) {
                jobs.add(j.str("component"));
            }
        }
        Map<String, Set<String>> entries = new HashMap<>();
        Map<String, List<String>> entryNames = new HashMap<>();
        for (Doc ep : entities.getOrDefault("entry_points", List.of())) {
            String comp = ep.str("component");
            if (!blank(comp)) {
                entries.computeIfAbsent(comp, k -> new HashSet<>()).add(ep.str("kind"));
            }
            if (comp != null) {
                entryNames.computeIfAbsent(comp, k -> new ArrayList<>()).add(ep.str("name"));
            }
        }
        Set<String> cft = new HashSet<>();
        for (Doc it : entities.getOrDefault("integrations", List.of())) {
            if (Set.of("CFT", "FTP", "SFTP", "SCP").contains(it.str("type"))) {
                cft.addAll(it.<String>list("components"));
            }
        }
        Map<String, Set<String>> cats = new HashMap<>();
        for (FileRecord r : records) {
            if (!blank(r.component)) {
                cats.computeIfAbsent(r.component, k -> new HashSet<>()).add(r.category.value());
            }
        }
        Map<String, Set<String>> batchy = batchScripts(entities, records);
        Set<String> online = Set.of("web", "online-mainframe", "api", "frontend", "desktop");
        for (Component c : components) {
            c.entryPoints = new ArrayList<>(new TreeSet<>(entryNames.getOrDefault(c.name, List.of())));
            Set<String> kinds = services.getOrDefault(c.name, Set.of());
            Set<String> techs = screens.getOrDefault(c.name, Set.of());
            Set<String> ccats = cats.getOrDefault(c.name, Set.of());
            Set<String> ents = entries.getOrDefault(c.name, Set.of());
            Set<String> roles = new TreeSet<>();
            if (meets(kinds, WEB_SERVICE_KINDS) || meets(techs, Linker.PAGE_TECH)) {
                roles.add("web");
            }
            if (meets(spa.getOrDefault(c.name, Set.of()), Linker.SPA_TECH) || meets(ents, SPA_ENTRY_KINDS)) {
                roles.add("frontend");
            }
            if (meets(kinds, Linker.API_SERVICE_KINDS)) {
                roles.add("api");
            }
            if (kinds.contains("cics-transaction") || techs.contains("BMS")) {
                roles.add("online-mainframe");
            }
            if (meets(techs, DESKTOP_TECH) || meets(ents, DESKTOP_ENTRY_KINDS)) {
                roles.add("desktop");
            }
            // The main() of a desktop application opens its windows: not batch work.
            Set<String> batchEntries = new HashSet<>(Set.of("scheduler", "spring-batch", "windows-service"));
            if (!roles.contains("desktop")) {
                batchEntries.add("main");
            }
            boolean programBatch = jobs.contains(c.name)
                    || meets(kinds, Set.of("cobol-program", "c-executable", "batch-job"))
                    || meets(ents, batchEntries);
            Set<String> scripts = batchy.getOrDefault(c.name, Set.of());
            if (Kinds.BATCH_SHARED.equals(c.detectedBy)) {
                scripts = Set.of(); // helpers the batches beside it share (decision 167)
            }
            if (meets(roles, online)) {
                // in an online application, only scripts filed as batch are batch work (deploy and
                // admin scripts often run SQL too)
                Set<String> kept = new HashSet<>();
                for (String p : scripts) {
                    if (Kinds.inBatchFolder(p)) {
                        kept.add(p);
                    }
                }
                scripts = kept;
            }
            if (programBatch || !scripts.isEmpty()) {
                roles.add("batch");
            }
            if (cft.contains(c.name) && Set.of("script", "config", "doc", "other", "job").containsAll(ccats)) {
                roles.add("cft");
            }
            if (ccats.contains("sql")) {
                roles.add("db");
            }
            if (ccats.contains("script") && !roles.contains("batch") && !roles.contains("cft")) {
                roles.add("script");
            }
            if (ccats.contains("source") && Set.of("db", "script").containsAll(roles)) {
                roles.add("library");
            }
            if (!ccats.isEmpty() && Set.of("config", "doc", "other", "build", "binary").containsAll(ccats) && ccats.contains("config")) {
                roles.add("config");
            }
            if (fixed.contains(c.name)) {
                roles.add(c.type.value());
                c.roles = new ArrayList<>(roles);
                continue;
            }
            if (roles.contains("web")) {
                c.type = ComponentType.WEB;
            } else if (roles.contains("frontend")) {
                c.type = ComponentType.FRONTEND;
            } else if (roles.contains("api")) {
                c.type = ComponentType.API;
            } else if (roles.contains("desktop")) {
                c.type = ComponentType.DESKTOP;
            } else if (roles.contains("online-mainframe")) {
                c.type = ComponentType.ONLINE_MAINFRAME;
            } else if (programBatch || (roles.contains("batch")
                    && (c.type == ComponentType.SCRIPT || c.type == ComponentType.BATCH || c.type == ComponentType.LIBRARY))) {
                c.type = ComponentType.BATCH;
            } else if (roles.contains("cft")) {
                c.type = ComponentType.CFT;
            }
            // Otherwise the inventory-based type from phase 1 stands.
            if (c.type == ComponentType.BATCH) {
                roles.remove("script");
                roles.remove("library");
            }
            if (c.type != ComponentType.UNKNOWN) {
                roles.add(c.type.value());
            }
            c.roles = new ArrayList<>(roles);
        }
    }
}
