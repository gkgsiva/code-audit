package com.legacyaudit.engine.components;

import com.legacyaudit.engine.inventory.Paths;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.ComponentType;
import com.legacyaudit.model.facts.FileRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Groups the components found by the folder and build-file rules (decision 176), for
 * {@code inventory.component_grouping: auto}.
 *
 * <ol>
 *   <li>Maven / Gradle (the pom decides): every module that runs on its own is a deployable (a war,
 *       ear, rar or sar, a Spring Boot or other runnable jar, a module whose files make it web,
 *       batch, desktop, front end or API). A library module goes into the deployable that uses it,
 *       read from the {@code <dependencies>} of the poms ({@code project(':core')} in a
 *       build.gradle), directly or through other libraries. A library used by several deployables,
 *       or by none, stays a component of its own.
 *   <li>Part folders (technology decides): in a Maven / Gradle group folder, the folders beside
 *       the modules are one component {@code <group>-scripts-config}. Without a build file, part
 *       folders found side by side are grouped by technology: COBOL and BMS are
 *       {@code cobol-online}, JCL and PROCLIB {@code jcl-jobs}, SQL folders {@code database}, the
 *       batches of one batch folder one component named after it, configuration and utility
 *       scripts {@code shared-scripts-config}. Configuration and scripts beside exactly one
 *       application go into it.
 * </ol>
 */
final class Grouping {

    private static final Set<String> DEPLOY_PACKAGING = Set.of("war", "ear", "rar", "sar", "har", "jboss-sar", "app", "spring-boot");
    private static final Set<ComponentType> RUNS_ON_ITS_OWN = Set.of(
            ComponentType.WEB, ComponentType.BATCH, ComponentType.DESKTOP, ComponentType.FRONTEND, ComponentType.API,
            ComponentType.ONLINE_MAINFRAME);
    private static final Set<String> MAINFRAME_ONLINE = Set.of("cobol", "bms", "pli", "assembler", "asm", "mfs");
    private static final Set<Category> SUPPORT_CATS = Set.of(Category.SCRIPT, Category.CONFIG);
    private static final Set<Category> NOT_TECH = Set.of(Category.DOC, Category.OTHER, Category.BUILD, Category.BINARY);

    private record Family(String kind, String extra) {}

    private Grouping() {}

    /** The candidates after grouping; {@code members} is updated for the new and grown ones. */
    static List<Candidate> group(List<Candidate> candidates, Map<Candidate, List<FileRecord>> members,
            Map<String, Map<String, String>> groupNames) {
        List<Candidate> live = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!members.getOrDefault(c, List.of()).isEmpty()) {
                live.add(c);
            }
        }
        live = foldLibraries(live, members);
        return groupParts(live, members, groupNames);
    }

    private static List<FileRecord> files(Map<Candidate, List<FileRecord>> members, Candidate c) {
        return members.computeIfAbsent(c, k -> new ArrayList<>());
    }

    // ---- 1. library modules into their deployable ---------------------------------------------

    private static boolean deployable(Candidate c, List<FileRecord> files) {
        if (DEPLOY_PACKAGING.contains(Objects.requireNonNullElse(c.packaging, "")) || c.runnable) {
            return true;
        }
        return RUNS_ON_ITS_OWN.contains(Kinds.inferType(files, c.path));
    }

    private static List<Candidate> foldLibraries(List<Candidate> cands, Map<Candidate, List<FileRecord>> members) {
        Map<String, List<Candidate>> byRoot = new LinkedHashMap<>();
        for (Candidate c : cands) {
            if (c.detectedBy.equals("build-file") && c.jvmModule) {
                byRoot.computeIfAbsent(c.root, k -> new ArrayList<>()).add(c);
            }
        }
        Set<Candidate> gone = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<Candidate> mods : byRoot.values()) {
            Map<String, Candidate> byArtifact = new HashMap<>();
            Map<String, Candidate> byPath = new HashMap<>();
            for (Candidate c : mods) {
                if (c.artifact != null && !c.artifact.isEmpty()) {
                    byArtifact.put(c.artifact, c);
                }
                byPath.put(c.path, c);
            }
            Set<Candidate> deploy = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Candidate c : mods) {
                if (deployable(c, files(members, c))) {
                    deploy.add(c);
                }
            }
            Map<Candidate, List<Candidate>> users = new IdentityHashMap<>();
            for (Candidate d : mods) {
                if (!deploy.contains(d)) {
                    continue;
                }
                List<Candidate> todo = uses(d, mods, byArtifact, byPath);
                Set<Candidate> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                seen.add(d);
                while (!todo.isEmpty()) {
                    Candidate lib = todo.removeLast();
                    if (seen.contains(lib) || deploy.contains(lib)) {
                        continue;
                    }
                    seen.add(lib);
                    List<Candidate> u = users.computeIfAbsent(lib, k -> new ArrayList<>());
                    if (!u.contains(d)) {
                        u.add(d);
                    }
                    todo.addAll(uses(lib, mods, byArtifact, byPath));
                }
            }
            List<Candidate> byPathOrder = new ArrayList<>(mods);
            byPathOrder.sort((a, b) -> a.path.compareTo(b.path));
            for (Candidate lib : byPathOrder) {
                List<Candidate> target = users.getOrDefault(lib, List.of());
                if (deploy.contains(lib) || target.size() != 1) {
                    continue; // shared by several deployables, or used by none
                }
                Candidate d = target.getFirst();
                if (d.parts.isEmpty()) {
                    d.parts = new ArrayList<>(List.of(d.path));
                }
                d.parts.add(lib.path);
                TreeSet<String> bf = new TreeSet<>(d.buildFiles);
                bf.addAll(lib.buildFiles);
                d.buildFiles = new ArrayList<>(bf);
                files(members, d).addAll(members.remove(lib));
                gone.add(lib);
            }
        }
        return cands.stream().filter(c -> !gone.contains(c)).toList();
    }

    private static List<Candidate> uses(Candidate c, List<Candidate> mods, Map<String, Candidate> byArtifact,
            Map<String, Candidate> byPath) {
        List<Candidate> out = new ArrayList<>();
        for (String dep : c.deps) {
            Candidate hit = byArtifact.get(dep);
            if (hit == null) {
                hit = byPath.get(dep);
            }
            if (hit == null) { // a Gradle project path: 'core' for the folder apps/core
                hit = mods.stream().filter(m -> m.path.endsWith("/" + dep)).findFirst().orElse(null);
            }
            if (hit != null && hit != c && !Candidate.contains(out, hit)) {
                out.add(hit);
            }
        }
        return out;
    }

    // ---- 2. part folders by technology --------------------------------------------------------

    private static Family family(Candidate c, List<FileRecord> files) {
        if (c.batchKid) {
            return new Family("batch", Paths.parent(c.path));
        }
        if (c.detectedBy.equals(Kinds.BATCH_SHARED)) {
            return new Family("batch", c.path);
        }
        List<FileRecord> tech = files.stream().filter(f -> !NOT_TECH.contains(f.category) && f.category != Category.TEST).toList();
        Set<Category> cats = new java.util.HashSet<>();
        TreeSet<String> langs = new TreeSet<>();
        for (FileRecord f : tech) {
            cats.add(f.category);
            langs.add(f.language);
        }
        if (langs.stream().anyMatch(MAINFRAME_ONLINE::contains)) {
            return new Family("cobol", "");
        }
        if (cats.contains(Category.JOB) && Set.of(Category.JOB, Category.CONFIG).containsAll(cats)) {
            return new Family("jcl", "");
        }
        if (!cats.isEmpty() && Set.of(Category.SQL).containsAll(cats)) {
            return new Family("db", "");
        }
        if (SUPPORT_CATS.containsAll(cats)) {
            if (tech.stream().anyMatch(f -> f.category == Category.SCRIPT && Kinds.inBatchFolder(f.path))) {
                return new Family("batch", "");
            }
            return new Family("support", "");
        }
        String main = null;
        long best = Long.MIN_VALUE;
        for (String lang : langs) { // the first of the largest, in name order
            long loc = tech.stream().filter(f -> f.language.equals(lang)).mapToLong(f -> f.locCode).sum();
            if (loc > best) {
                best = loc;
                main = lang;
            }
        }
        return new Family("code", main);
    }

    private static boolean hasTech(List<FileRecord> files) {
        return files.stream().anyMatch(f -> !NOT_TECH.contains(f.category));
    }

    private static String common(List<String> paths) {
        List<String[]> split = paths.stream().map(p -> p.isEmpty() ? new String[0] : p.split("/")).toList();
        int n = split.stream().mapToInt(a -> a.length).min().orElse(0);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String part = split.getFirst()[i];
            final int idx = i;
            if (split.stream().anyMatch(a -> !a[idx].equals(part))) {
                break;
            }
            out.add(part);
        }
        return String.join("/", out);
    }

    private static boolean isPart(Candidate c) {
        if (c.app || c.detectedBy.equals("build-file") || c.detectedBy.equals("config")) {
            return false;
        }
        if (c.inGroup) {
            return true;
        }
        String name = Paths.name(c.path).toLowerCase(java.util.Locale.ROOT);
        return c.batchKid || c.detectedBy.equals(Kinds.BATCH_SHARED) || c.detectedBy.equals("root") || Kinds.PART_FOLDERS.contains(name);
    }

    private static String name(Family family, List<FileRecord> files) {
        return switch (family.kind()) {
            case "cobol" -> files.stream().anyMatch(f -> f.language.equals("bms")) ? "cobol-online" : "cobol-programs";
            case "batch" -> {
                String n = Paths.name(family.extra());
                yield n.isEmpty() ? "batch" : n;
            }
            case "jcl" -> "jcl-jobs";
            case "db" -> "database";
            case "support" -> "shared-scripts-config";
            default -> family.extra() + "-code";
        };
    }

    private static List<Candidate> groupParts(List<Candidate> cands, Map<Candidate, List<FileRecord>> members,
            Map<String, Map<String, String>> groupNames) {
        Map<List<String>, List<Candidate>> levels = new LinkedHashMap<>();
        for (Candidate c : cands) {
            if (!c.detectedBy.equals("config")) {
                levels.computeIfAbsent(List.of(c.root, c.level), k -> new ArrayList<>()).add(c);
            }
        }
        List<Candidate> out = new ArrayList<>(cands.stream().filter(c -> c.detectedBy.equals("config")).toList());
        for (Map.Entry<List<String>, List<Candidate>> e : levels.entrySet()) {
            String root = e.getKey().get(0);
            String level = e.getKey().get(1);
            List<Candidate> items = e.getValue();
            Map<String, String> names = groupNames.getOrDefault(root, Map.of());
            boolean inGroup = names.containsKey(level);
            // A part with no code, script, SQL or configuration (a parent pom, a README) is left
            // alone: it belongs to no component (rule 3).
            List<Candidate> parts = items.stream().filter(c -> isPart(c) && hasTech(files(members, c))).toList();
            List<Candidate> others = items.stream().filter(c -> !Candidate.contains(parts, c)).toList();
            Map<Family, List<Candidate>> buckets = new LinkedHashMap<>();
            if (inGroup) {
                if (!parts.isEmpty()) {
                    buckets.put(new Family("group", level), new ArrayList<>(parts));
                }
            } else {
                for (Candidate c : parts) {
                    buckets.computeIfAbsent(family(c, files(members, c)), k -> new ArrayList<>()).add(c);
                }
            }
            List<Candidate> grouped = new ArrayList<>();
            for (Map.Entry<Family, List<Candidate>> b : buckets.entrySet()) {
                List<Candidate> bucket = b.getValue();
                if (bucket.size() == 1) {
                    grouped.add(bucket.getFirst());
                    continue;
                }
                List<FileRecord> files = new ArrayList<>();
                for (Candidate c : bucket) {
                    List<FileRecord> m = members.remove(c);
                    if (m != null) {
                        files.addAll(m);
                    }
                }
                String name;
                if (b.getKey().kind().equals("group")) {
                    String g = names.get(level);
                    if (g == null || g.isEmpty()) {
                        g = Paths.name(level);
                    }
                    if (g.isEmpty()) {
                        g = root;
                    }
                    name = g + "-scripts-config";
                } else {
                    name = name(b.getKey(), files);
                }
                List<String> paths = new ArrayList<>(bucket.stream().map(c -> c.path).toList());
                paths.sort(String::compareTo);
                Candidate merged = new Candidate(root, common(paths), Kinds.GROUPED);
                merged.name = name;
                merged.inGroup = inGroup;
                merged.level = level;
                merged.parts = paths;
                members.put(merged, files);
                grouped.add(merged);
            }
            // Configuration and scripts beside exactly one application belong to it.
            if (others.size() == 1) {
                Candidate app = others.getFirst();
                for (Candidate c : List.copyOf(grouped)) {
                    if (!family(c, files(members, c)).kind().equals("support") || c.batchKid) {
                        continue;
                    }
                    if (app.parts.isEmpty()) {
                        app.parts = new ArrayList<>(List.of(app.path));
                    }
                    app.parts.addAll(!c.parts.isEmpty() ? c.parts : List.of(c.path));
                    List<FileRecord> moved = members.remove(c);
                    files(members, app).addAll(moved == null ? List.of() : moved);
                    Candidate.removeFirst(grouped, c);
                }
            }
            out.addAll(others);
            out.addAll(grouped);
        }
        return out;
    }
}
