package com.legacyaudit.engine.components;

import static com.legacyaudit.engine.components.Kinds.BATCH_FOLDERS;
import static com.legacyaudit.engine.components.Kinds.BATCH_SHARED;
import static com.legacyaudit.engine.components.Kinds.CODE_FOLDERS;
import static com.legacyaudit.engine.components.Kinds.GROUPED;
import static com.legacyaudit.engine.components.Kinds.PART_FOLDERS;
import static com.legacyaudit.engine.components.Kinds.SHARED_FOLDERS;
import static com.legacyaudit.engine.components.Kinds.TECH_CATEGORIES;

import com.legacyaudit.engine.inventory.Paths;
import com.legacyaudit.model.config.AuditConfig.ComponentSpec;
import com.legacyaudit.model.facts.Component;
import com.legacyaudit.model.facts.ComponentType;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/**
 * Default component detection from build files and folder layout. Rules, per source root:
 *
 * <ol>
 *   <li>Every folder holding a build or project file (pom.xml, build.xml, build.gradle, Makefile,
 *       an Eclipse .project, CMakeLists.txt, a Visual Studio project, package.json, angular.json,
 *       bower.json, ...) is a component root.
 *   <li>If the source root itself has no build file, the folder layout decides (decision 114):
 *       part folders (src, WebContent, conf, scripts, sql, jcl, ...) are never components on their
 *       own next to code; a folder holding code folders is one component; a folder holding only
 *       other application folders is a container; at a root that is not an application, every
 *       top-level folder is a component and loose files go to a component named after the root.
 *   <li>Maven and Gradle decide when they are there (decision 166): a pom with packaging pom and a
 *       Gradle root with settings.gradle are groups, not components; each module they list is one.
 *   <li>A batch folder holding two or more batch folders is split: each is a batch component, and
 *       what they share is one component of its own (decision 167).
 *   <li>Components listed in audit.yaml take precedence over 1 to 4 for the files under them.
 * </ol>
 *
 * A file belongs to the deepest component root above it. Then the components are grouped
 * ({@code inventory.component_grouping: auto}, decision 176; see {@link Grouping}).
 */
public final class ComponentDetector {

    private static final Logger LOG = Logger.getLogger("legacy_audit");

    private static final int MAX_CONTAINER_DEPTH = 3;

    /** The detected components, and file id -> component name. */
    public record Result(List<Component> components, Map<String, String> fileToComponent) {}

    private ComponentDetector() {}

    /**
     * @param read the text of a build file of a root (root name, path relative to the root), or
     *     null; without it the folder layout decides
     * @param grouping {@code auto} (group them, decision 176) or {@code module}
     */
    public static Result detect(List<FileRecord> records, List<String> rootNames, List<ComponentSpec> specs,
            BiFunction<String, String, String> read, String grouping) {
        boolean multi = rootNames.size() > 1;
        Map<String, List<FileRecord>> byRoot = new LinkedHashMap<>();
        for (FileRecord r : records) {
            byRoot.computeIfAbsent(r.root, k -> new ArrayList<>()).add(r);
        }
        List<Candidate> candidates = new ArrayList<>();
        List<Candidate> manual = new ArrayList<>();
        Map<String, Map<String, String>> groups = new HashMap<>(); // root -> group folder -> group name
        for (ComponentSpec spec : specs) {
            Candidate c = new Candidate(spec.root() != null ? spec.root() : rootNames.getFirst(), spec.path(), "config");
            c.name = spec.name();
            c.type = spec.type();
            manual.add(c);
        }

        for (String root : rootNames) {
            List<FileRecord> files = byRoot.getOrDefault(root, List.of());
            List<String> rels = files.stream().map(f -> relInRoot(f, multi)).toList();
            Map<String, List<String>> buildDirs = new LinkedHashMap<>();
            for (String rel : rels) {
                if (Kinds.isProjectFile(Paths.name(rel))) {
                    buildDirs.computeIfAbsent(Paths.parent(rel), k -> new ArrayList<>()).add(rel);
                }
            }
            Map<String, BuildInfo> info = buildInfo(root, buildDirs, read);
            Map<String, BuildInfo> gradle = gradleBuilds(root, buildDirs, read);
            Set<String> present = new HashSet<>();
            for (String rel : rels) {
                for (String p = Paths.parent(rel); !p.isEmpty(); p = Paths.parent(p)) {
                    present.add(p);
                }
                present.add(Paths.parent(rel));
            }
            for (BuildInfo bi : List.copyOf(info.values())) {
                for (String m : bi.modules()) { // a Gradle project needs no build file of its own
                    if (!buildDirs.containsKey(m) && present.contains(m)) {
                        buildDirs.put(m, new ArrayList<>());
                    }
                }
            }
            Set<String> groupDirs = groups(rels, buildDirs, info);
            Map<String, String> names = new HashMap<>();
            for (String g : groupDirs) {
                BuildInfo gi = info.get(g);
                String artifact = gi != null && gi.artifact() != null && !gi.artifact().isEmpty() ? gi.artifact() : null;
                names.put(g, artifact != null ? artifact : (g.isEmpty() ? root : Paths.name(g)));
            }
            groups.put(root, names);
            for (Map.Entry<String, List<String>> e : buildDirs.entrySet()) {
                String d = e.getKey();
                if (groupDirs.contains(d)) {
                    continue;
                }
                BuildInfo mod = info.get(d);
                BuildInfo gmod = gradle.get(d);
                boolean inBuild = info.values().stream().anyMatch(bi -> bi.modules().contains(d));
                Candidate c = new Candidate(root, d, "build-file");
                c.buildFiles = sortedCopy(e.getValue());
                c.artifact = mod != null ? mod.artifact() : null;
                c.packaging = mod != null ? mod.packaging() : null;
                c.level = groupDirs.stream().filter(g -> Paths.under(d, g)).max(Comparator.comparingInt(String::length)).orElse("");
                c.jvmModule = (mod != null && mod.packaging() != null) || gmod != null || inBuild;
                if (mod != null) {
                    c.deps.addAll(mod.deps());
                }
                if (gmod != null) {
                    c.deps.addAll(gmod.deps());
                }
                c.runnable = (mod != null && mod.runnable()) || (gmod != null && gmod.runnable());
                candidates.add(c);
            }
            List<String> layoutRoots = new ArrayList<>(new TreeSet<>(groupDirs));
            if (!buildDirs.containsKey("")) {
                layoutRoots.add("");
            }
            for (String g : layoutRoots) {
                int cut = g.isEmpty() ? 0 : g.length() + 1;
                List<String> subRels = new ArrayList<>();
                for (String rel : rels) {
                    if (g.isEmpty() || rel.startsWith(g + "/")) {
                        subRels.add(rel.substring(cut));
                    }
                }
                Set<String> subBuild = new HashSet<>();
                for (String d : buildDirs.keySet()) {
                    if (!d.equals(g) && Paths.under(d, g)) {
                        subBuild.add(d.substring(cut));
                    }
                }
                for (Candidate c : layoutCandidates(root, subRels, subBuild)) {
                    c.path = !g.isEmpty() && !c.path.isEmpty() ? g + "/" + c.path : (!g.isEmpty() ? g : c.path);
                    c.inGroup = groupDirs.contains(g);
                    c.level = g;
                    candidates.add(c);
                }
            }
        }

        Map<Candidate, List<FileRecord>> members = new IdentityHashMap<>();
        for (Map.Entry<String, List<FileRecord>> e : byRoot.entrySet()) {
            String root = e.getKey();
            List<Candidate> rootManual = manual.stream().filter(c -> c.root.equals(root)).toList();
            List<Candidate> rootAuto = candidates.stream().filter(c -> c.root.equals(root)).toList();
            for (FileRecord f : e.getValue()) {
                String rel = relInRoot(f, multi);
                Candidate best = null;
                for (List<Candidate> pool : List.of(rootManual, rootAuto)) {
                    for (Candidate c : pool) {
                        if (Paths.under(rel, c.path) && (best == null || c.path.length() > best.path.length())) {
                            best = c;
                        }
                    }
                    if (best != null) {
                        break;
                    }
                }
                if (best != null) {
                    members.computeIfAbsent(best, k -> new ArrayList<>()).add(f);
                }
            }
        }
        if (grouping.equals("auto")) {
            candidates = Grouping.group(candidates, members, groups);
        }

        List<Component> components = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        Map<String, String> fileToName = new LinkedHashMap<>();
        Set<String> seenPaths = new HashSet<>();
        List<Candidate> ordered = new ArrayList<>(manual);
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing((Candidate c) -> c.root).thenComparing(c -> c.path));
        ordered.addAll(sorted);
        for (Candidate cand : ordered) {
            List<FileRecord> files = members.getOrDefault(cand, List.of());
            if (files.isEmpty()) {
                continue;
            }
            if (cand.inGroup && files.stream().noneMatch(f -> TECH_CATEGORIES.contains(f.category))) {
                continue; // a parent pom, a README: part of the group, not a component (rule 3)
            }
            if (!cand.detectedBy.equals("config") && !cand.detectedBy.equals(GROUPED)) {
                if (!seenPaths.add(cand.root + "\u001f" + cand.path)) {
                    continue;
                }
            }
            String name = cand.name != null ? cand.name : (!cand.path.isEmpty() ? Paths.name(cand.path) : cand.root);
            if (taken.contains(name)) {
                name = !cand.path.isEmpty() ? cand.root + "-" + cand.path.replace('/', '-') : cand.root;
            }
            String base = name;
            for (int n = 2; taken.contains(name); n++) {
                name = base + "-" + n;
            }
            taken.add(name);
            ComponentType ctype;
            if (cand.type != null) {
                ctype = cand.type;
            } else if (cand.detectedBy.equals(BATCH_SHARED)) {
                ctype = Kinds.sharedType(files);
            } else {
                ctype = Kinds.inferType(files, cand.path);
                if ("war".equals(cand.packaging) && (ctype == ComponentType.LIBRARY || ctype == ComponentType.UNKNOWN)) {
                    ctype = ComponentType.WEB;
                }
            }
            Set<String> tech = new TreeSet<>();
            for (FileRecord f : files) {
                if (TECH_CATEGORIES.contains(f.category)) {
                    tech.add(f.language);
                }
            }
            List<String> evidenceFiles = !cand.buildFiles.isEmpty() ? cand.buildFiles : List.of(files.getFirst().path);
            if (multi) {
                evidenceFiles = !cand.buildFiles.isEmpty()
                        ? cand.buildFiles.stream().map(p -> cand.root + "/" + p).toList()
                        : List.of(files.getFirst().path);
            }
            Map<String, String> groupNames = groups.getOrDefault(cand.root, Map.of());
            List<String> chain = groupNames.keySet().stream()
                    .filter(g -> !g.equals(cand.path) && Paths.under(cand.path, g))
                    .sorted(Comparator.comparingInt(String::length))
                    .toList();
            Component comp = new Component();
            comp.id = cand.detectedBy.equals(GROUPED)
                    ? Fact.makeId("component", cand.root, cand.path, GROUPED, name)
                    : Fact.makeId("component", cand.root, cand.path, cand.detectedBy);
            comp.name = name;
            comp.type = ctype;
            comp.root = cand.root;
            comp.path = cand.path;
            comp.buildFiles = !cand.buildFiles.isEmpty() ? new ArrayList<>(evidenceFiles) : new ArrayList<>();
            comp.technology = new ArrayList<>(tech);
            comp.roles = ctype != ComponentType.UNKNOWN ? new ArrayList<>(List.of(ctype.value())) : new ArrayList<>();
            comp.composition = Kinds.composition(files);
            comp.files = files.size();
            comp.locCode = files.stream().mapToInt(f -> f.locCode).sum();
            comp.detectedBy = cand.detectedBy;
            comp.buildGroup = new ArrayList<>(chain.stream().map(groupNames::get).toList());
            comp.artifact = cand.artifact;
            comp.packaging = cand.packaging;
            comp.parts = new ArrayList<>(cand.parts);
            comp.evidence = evidenceFiles.stream().map(Evidence::of).toList();
            components.add(comp);
            for (FileRecord f : files) {
                fileToName.put(f.id, name);
            }
        }
        return new Result(components, fileToName);
    }

    private static List<String> sortedCopy(List<String> items) {
        List<String> out = new ArrayList<>(items);
        out.sort(String::compareTo);
        return out;
    }

    /** File path relative to its own root (strip the root prefix added for multi-root runs). */
    static String relInRoot(FileRecord rec, boolean multiRoot) {
        if (multiRoot && rec.path.startsWith(rec.root + "/")) {
            return rec.path.substring(rec.root.length() + 1);
        }
        return rec.path;
    }

    /** Rule 2: components of a root without a build file of its own, from the folder layout. */
    static List<Candidate> layoutCandidates(String root, List<String> rels, Set<String> buildDirs) {
        Map<String, Set<String>> children = new HashMap<>();
        Set<String> direct = new HashSet<>(); // folders holding files directly
        for (String rel : rels) {
            boolean underBuild = false;
            for (String d : buildDirs) {
                if (!d.isEmpty() && Paths.under(rel, d)) {
                    underBuild = true;
                    break;
                }
            }
            if (underBuild) {
                continue;
            }
            String parent = Paths.parent(rel);
            direct.add(parent);
            while (!parent.isEmpty()) {
                String up = Paths.parent(parent);
                children.computeIfAbsent(up, k -> new HashSet<>()).add(parent);
                parent = up;
            }
        }
        Layout layout = new Layout(root, children, direct);
        if (layout.hasCode("")) {
            Candidate c = new Candidate(root, "", "root");
            c.app = true;
            layout.out.add(c);
            layout.nestedApps("");
        } else {
            for (String top : layout.kids("")) {
                layout.component(top, 1);
            }
            layout.out.add(new Candidate(root, "", "root"));
        }
        return layout.out;
    }

    private static final class Layout {
        final String root;
        final Map<String, Set<String>> children;
        final Set<String> direct;
        final List<Candidate> out = new ArrayList<>();

        Layout(String root, Map<String, Set<String>> children, Set<String> direct) {
            this.root = root;
            this.children = children;
            this.direct = direct;
        }

        static String name(String folder) {
            return Paths.name(folder).toLowerCase(Locale.ROOT);
        }

        /** Sub-folders, sorted. */
        List<String> kids(String folder) {
            List<String> k = new ArrayList<>(children.getOrDefault(folder, Set.of()));
            k.sort(String::compareTo);
            return k;
        }

        boolean hasCode(String folder) {
            return children.getOrDefault(folder, Set.of()).stream().anyMatch(k -> CODE_FOLDERS.contains(name(k)));
        }

        Candidate add(String path, String by, boolean app) {
            Candidate c = new Candidate(root, path, by);
            c.app = app;
            out.add(c);
            return c;
        }

        /** Application folders inside an application become components of their own. */
        void nestedApps(String folder) {
            for (String kid : kids(folder)) {
                if (PART_FOLDERS.contains(name(kid))) {
                    continue;
                }
                if (hasCode(kid)) {
                    add(kid, "folder", true);
                }
                nestedApps(kid);
            }
        }

        /** The batches of a batch folder holding several (rule 4), or none when it is one. */
        List<String> batchKids(String folder) {
            if (!BATCH_FOLDERS.contains(name(folder)) || hasCode(folder)) {
                return List.of();
            }
            List<String> kids = kids(folder).stream()
                    .filter(k -> !PART_FOLDERS.contains(name(k)) && !SHARED_FOLDERS.contains(name(k)))
                    .toList();
            return kids.size() >= 2 ? kids : List.of();
        }

        void component(String folder, int depth) {
            Set<String> kids = children.getOrDefault(folder, Set.of());
            List<String> batches = batchKids(folder);
            if (!batches.isEmpty()) {
                add(folder, BATCH_SHARED, false); // what the batches share
                for (String kid : batches) {
                    add(kid, "folder", hasCode(kid)).batchKid = true;
                    nestedApps(kid);
                }
                return;
            }
            boolean container = !hasCode(folder)
                    && !direct.contains(folder)
                    && kids.size() >= 2
                    && kids.stream().noneMatch(k -> PART_FOLDERS.contains(name(k)))
                    && depth < MAX_CONTAINER_DEPTH;
            if (container) {
                for (String kid : kids(folder)) {
                    component(kid, depth + 1);
                }
                return;
            }
            if (!direct.contains(folder) && kids.size() == 1 && depth < MAX_CONTAINER_DEPTH
                    && !batchKids(kids.iterator().next()).isEmpty()) {
                // a wrapper folder around a batch folder of several batches (ops/batch/...)
                component(kids.iterator().next(), depth + 1);
                return;
            }
            add(folder, "folder", hasCode(folder));
            nestedApps(folder);
        }
    }

    /** Per build folder, what its pom.xml / settings.gradle says (when it can be read). */
    private static Map<String, BuildInfo> buildInfo(String root, Map<String, List<String>> buildDirs,
            BiFunction<String, String, String> read) {
        Map<String, BuildInfo> out = new LinkedHashMap<>();
        if (read == null) {
            return out;
        }
        for (Map.Entry<String, List<String>> e : buildDirs.entrySet()) {
            String d = e.getKey();
            for (String rel : e.getValue()) {
                String low = Paths.name(rel).toLowerCase(Locale.ROOT);
                if (!low.equals("pom.xml") && !low.equals("settings.gradle") && !low.equals("settings.gradle.kts")) {
                    continue;
                }
                String text = read.apply(root, rel);
                if (text == null) {
                    continue;
                }
                BuildInfo bi;
                try {
                    bi = low.equals("pom.xml") ? BuildInfo.readPom(d, text) : BuildInfo.readGradleSettings(d, text);
                } catch (BuildInfo.XmlError err) {
                    LOG.info("Components: " + root + "/" + rel + " not read (" + err.getMessage() + ")");
                    continue;
                }
                if (low.equals("pom.xml") || !out.containsKey(d)) {
                    if (out.containsKey(d) && out.get(d).gradleRoot()) { // a folder with both: keep both lists
                        Set<String> both = new LinkedHashSet<>(bi.modules());
                        both.addAll(out.get(d).modules());
                        bi = bi.withModules(new ArrayList<>(both));
                    }
                    out.put(d, bi);
                } else {
                    Set<String> both = new LinkedHashSet<>(out.get(d).modules());
                    both.addAll(bi.modules());
                    out.put(d, out.get(d).withModules(new ArrayList<>(both)));
                }
            }
        }
        return out;
    }

    /** Per folder with a {@code build.gradle}, the projects it depends on and whether it runs. */
    private static Map<String, BuildInfo> gradleBuilds(String root, Map<String, List<String>> buildDirs,
            BiFunction<String, String, String> read) {
        Map<String, BuildInfo> out = new LinkedHashMap<>();
        if (read == null) {
            return out;
        }
        for (Map.Entry<String, List<String>> e : buildDirs.entrySet()) {
            for (String rel : e.getValue()) {
                String low = Paths.name(rel).toLowerCase(Locale.ROOT);
                if (!low.equals("build.gradle") && !low.equals("build.gradle.kts")) {
                    continue;
                }
                String text = read.apply(root, rel);
                if (text != null) {
                    out.put(e.getKey(), BuildInfo.readGradleBuild(e.getKey(), text));
                }
            }
        }
        return out;
    }

    /**
     * Build folders that are Maven parents / aggregators or Gradle roots: groups of modules, not
     * components (rule 3). A pom that could not be read is a group when other build folders sit
     * under it and it has no code folder of its own.
     */
    private static Set<String> groups(List<String> rels, Map<String, List<String>> buildDirs, Map<String, BuildInfo> info) {
        Map<String, Set<String>> first = new HashMap<>(); // folder -> names of its direct sub-folders
        for (String rel : rels) {
            String[] parts = Paths.dirs(rel);
            for (int n = 0; n < parts.length; n++) {
                String folder = String.join("/", java.util.Arrays.copyOfRange(parts, 0, n));
                first.computeIfAbsent(folder, k -> new HashSet<>()).add(parts[n].toLowerCase(Locale.ROOT));
            }
        }
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, List<String>> e : buildDirs.entrySet()) {
            String d = e.getKey();
            Set<String> names = new HashSet<>();
            e.getValue().forEach(f -> names.add(Paths.name(f).toLowerCase(Locale.ROOT)));
            boolean hasCode = first.getOrDefault(d, Set.of()).stream().anyMatch(CODE_FOLDERS::contains);
            BuildInfo bi = info.get(d);
            if (bi != null && bi.packaging() != null) {
                if (bi.packaging().equals("pom")) {
                    out.add(d);
                }
            } else if (bi != null && bi.gradleRoot()) {
                if (!bi.modules().isEmpty() && !hasCode) {
                    out.add(d);
                }
            } else if (names.contains("pom.xml") || names.contains("settings.gradle") || names.contains("settings.gradle.kts")) {
                boolean nested = buildDirs.keySet().stream().anyMatch(o -> !o.equals(d) && Paths.under(o, d));
                if (nested && !hasCode) {
                    out.add(d);
                }
            }
        }
        return out;
    }
}
