package com.legacyaudit.engine.scan;

import com.legacyaudit.engine.components.ComponentDetector;
import com.legacyaudit.engine.inventory.FileAnalyzer;
import com.legacyaudit.engine.inventory.Languages;
import com.legacyaudit.engine.inventory.Walker;
import com.legacyaudit.engine.inventory.Walker.Filters;
import com.legacyaudit.engine.inventory.Walker.Skipped;
import com.legacyaudit.engine.inventory.Walker.Walked;
import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.ConfigEdits;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.model.config.Overrides;
import com.legacyaudit.model.facts.Component;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Origin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The components a scan would find, from file names only (only {@code pom.xml} and
 * {@code settings.gradle} are read), with whether each is ticked and its type override: the
 * component picker of the settings page ({@code workspace.prescan} in the Python tool).
 */
public final class Prescan {

    private Prescan() {}

    public static Map<String, Object> run(LoadedConfig loaded) throws IOException {
        AuditConfig cfg = loaded.config();
        Overrides ov = Overrides.load(loaded.overridesPath());
        List<Sources.Root> roots = Sources.resolve(loaded);
        List<Path> toolOwned = new ArrayList<>(List.of(loaded.outputDir(), loaded.inputsDir(), loaded.path(), loaded.overridesPath()));
        for (String p : Scanner.copilotPaths()) {
            toolOwned.add(loaded.baseDir().resolve(p));
        }
        boolean multi = roots.size() > 1;
        List<FileRecord> records = new ArrayList<>();
        List<Skipped> leftOut = new ArrayList<>();
        List<String> exclude = new ArrayList<>(cfg.exclude());
        ov.filesInclude().forEach(p -> exclude.add("!" + p));
        AuditConfig.Inventory inv = cfg.inventory();
        for (Sources.Root root : roots) {
            Filters filters = Filters.of(cfg.include(), exclude, inv.useDefaultExcludes(), Walker.anchoredExcludes(root.path(), toolOwned),
                    inv.skipBuildOutput());
            for (Walked wf : Walker.walk(root.name(), root.path(), filters, root.subpaths(), leftOut)) {
                if (FileAnalyzer.skipByExtension(wf.rel(), inv.includeBinaries())) {
                    continue;
                }
                String path = multi ? root.name() + "/" + wf.rel() : wf.rel();
                FileRecord rec = new FileRecord();
                rec.id = Fact.makeId("file", root.name(), path);
                rec.path = path;
                rec.root = root.name();
                rec.origin = Origin.SOURCE;
                rec.language = Languages.detect(path, null);
                rec.category = Languages.category(path, rec.language, null);
                rec.sha256 = "";
                records.add(rec);
            }
        }
        Map<String, Path> rootPaths = new LinkedHashMap<>();
        roots.forEach(r -> rootPaths.put(r.name(), r.path()));
        ComponentDetector.Result detected = ComponentDetector.detect(records, roots.stream().map(Sources.Root::name).toList(),
                cfg.components(), (root, rel) -> readBuildFile(rootPaths, root, rel), inv.componentGrouping());
        Map<String, Map<String, Integer>> langs = new LinkedHashMap<>();
        detected.components().forEach(c -> langs.put(c.name, new LinkedHashMap<>()));
        for (FileRecord rec : records) {
            String comp = detected.fileToComponent().get(rec.id);
            if (comp != null) {
                langs.get(comp).merge(rec.language, 1, Integer::sum);
            }
        }
        Set<String> excludes = new HashSet<>(ov.filesExclude());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Component c : detected.components()) {
            List<String> folders = c.parts.isEmpty() ? List.of(c.path) : c.parts;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", c.name);
            row.put("root", c.root);
            row.put("path", c.path);
            row.put("detected_type", c.type.value());
            row.put("type_override", ov.type().containsKey(c.name) ? ov.type().get(c.name).value() : null);
            row.put("files", c.files);
            row.put("languages", mostCommon(langs.get(c.name), 6));
            row.put("detected_by", c.detectedBy);
            row.put("build_files", c.buildFiles);
            row.put("build_group", c.buildGroup);
            row.put("packaging", c.packaging);
            row.put("parts", c.parts);
            row.put("folders", folders);
            // files.exclude applies inside every root, so the pattern is root-relative
            row.put("included", folders.stream().noneMatch(p -> excludes.contains(ConfigEdits.componentExclude(p))));
            out.add(row);
        }
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Skipped sk : leftOut) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", multi ? sk.root() + "/" + sk.rel() : sk.rel());
            m.put("reason", sk.reason());
            m.put("files", sk.files());
            skipped.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> rootRows = new ArrayList<>();
        for (Sources.Root r : roots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("path", r.path().toString());
            rootRows.add(m);
        }
        result.put("roots", rootRows);
        result.put("files", records.size());
        result.put("components", out);
        result.put("excludes", new ArrayList<>(new TreeSet<>(excludes)));
        result.put("skipped", skipped);
        return result;
    }

    /** {@code Counter.most_common(n)}: highest counts first, ties in first-seen order. */
    static Map<String, Integer> mostCommon(Map<String, Integer> counts, int n) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue())); // stable
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : entries.subList(0, Math.min(n, entries.size()))) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static String readBuildFile(Map<String, Path> roots, String root, String rel) {
        Path base = roots.get(root);
        if (base == null) {
            return null;
        }
        Path p = base.resolve(rel);
        try {
            if (Files.size(p) > 2_000_000) {
                return null;
            }
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
