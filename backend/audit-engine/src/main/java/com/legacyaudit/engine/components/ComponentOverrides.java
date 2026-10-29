package com.legacyaudit.engine.components;

import com.legacyaudit.engine.inventory.GitIgnore;
import com.legacyaudit.model.config.Overrides;
import com.legacyaudit.model.facts.Component;
import com.legacyaudit.model.facts.ComponentType;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the {@code components:} part of {@code audit-overrides.yaml}: assign, rename and type.
 * Each override is reported with how many items it matched; unused ones become warnings so stale
 * overrides are noticed.
 */
public final class ComponentOverrides {

    private ComponentOverrides() {}

    /** The components after the overrides, sorted by name; file records are updated in place. */
    public static List<Component> apply(Overrides ov, List<FileRecord> records, List<Component> components,
            Map<String, Object> report, List<String> warnings) {
        Map<String, Component> byName = new LinkedHashMap<>();
        for (Component c : components) {
            byName.put(c.name, c);
        }

        // assign: move files matching a glob to a component (created if it does not exist).
        Map<String, Integer> assigned = new LinkedHashMap<>();
        ov.assign().forEach((pattern, target) -> {
            GitIgnore spec = GitIgnore.of(List.of(pattern));
            List<FileRecord> hits = records.stream().filter(r -> r.component != null && spec.matches(r.path)).toList();
            assigned.put(pattern, hits.size());
            if (hits.isEmpty()) {
                warnings.add("Override components.assign '" + pattern + "' matched no files");
                return;
            }
            if (!byName.containsKey(target)) {
                Component c = new Component();
                c.id = Fact.makeId("component", "override", target);
                c.name = target;
                c.root = hits.getFirst().root;
                c.path = "";
                c.detectedBy = "override";
                c.evidence = Evidence.list(hits.getFirst().path);
                byName.put(target, c);
            }
            for (FileRecord r : hits) {
                r.component = target;
            }
        });

        Map<String, Integer> renamed = new LinkedHashMap<>();
        ov.rename().forEach((old, now) -> {
            Component comp = byName.remove(old);
            renamed.put(old, comp != null ? 1 : 0);
            if (comp == null) {
                warnings.add("Override components.rename '" + old + "' matches no component");
                return;
            }
            comp.name = now;
            byName.put(now, comp);
            for (FileRecord r : records) {
                if (old.equals(r.component)) {
                    r.component = now;
                }
            }
        });

        Map<String, Integer> typed = new LinkedHashMap<>();
        ov.type().forEach((name, ctype) -> {
            Component comp = byName.get(name);
            typed.put(name, comp != null ? 1 : 0);
            if (comp == null) {
                warnings.add("Override components.type '" + name + "' matches no component");
                return;
            }
            comp.type = ctype;
            comp.detectedBy = comp.detectedBy.equals("override") ? comp.detectedBy : comp.detectedBy + "+override";
        });

        // Recompute per-component totals after files moved.
        Map<String, List<FileRecord>> members = new LinkedHashMap<>();
        for (FileRecord r : records) {
            if (r.component != null && !r.component.isEmpty()) {
                members.computeIfAbsent(r.component, k -> new ArrayList<>()).add(r);
            }
        }
        List<Component> result = new ArrayList<>();
        for (Component comp : byName.values()) {
            List<FileRecord> files = members.getOrDefault(comp.name, List.of());
            if (files.isEmpty()) {
                continue;
            }
            comp.files = files.size();
            comp.locCode = files.stream().mapToInt(f -> f.locCode).sum();
            if (comp.detectedBy.equals("override") && !ov.type().containsKey(comp.name)) {
                comp.type = Kinds.inferType(files, "");
            }
            result.add(comp);
        }
        report.put("file", "audit-overrides.yaml");
        report.put("files_include", ov.filesInclude());
        report.put("files_exclude", ov.filesExclude());
        report.put("components_assign", assigned);
        report.put("components_rename", renamed);
        report.put("components_type", typed);
        result.sort(Comparator.comparing(c -> c.name));
        return result;
    }

    /** For callers that only need the type of a set of files. */
    public static ComponentType typeOf(List<FileRecord> files) {
        return Kinds.inferType(files, "");
    }
}
