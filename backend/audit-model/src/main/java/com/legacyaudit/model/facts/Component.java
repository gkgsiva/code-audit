package com.legacyaudit.model.facts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A deployable or shared part of the application, found from build files and the folder layout. */
public final class Component implements Fact {

    public String id;
    public List<Evidence> evidence = List.of();
    public String name;
    public ComponentType type = ComponentType.UNKNOWN;
    /** Source root name. */
    public String root;
    /** Component folder relative to the source root ('' = root). */
    public String path;
    public List<String> buildFiles = new ArrayList<>();
    public List<String> technology = new ArrayList<>();
    /** Every kind of work found in the component; {@code type} is the main one. */
    public List<String> roles = new ArrayList<>();
    /** Files by category. */
    public Map<String, Integer> composition = new LinkedHashMap<>();
    public int files;
    public int locCode;
    public List<String> entryPoints = new ArrayList<>();
    /** build-file, folder, root, batch-shared, grouped, config or override (with "+override"). */
    public String detectedBy;
    /** Maven parent / aggregator poms or Gradle root projects the module sits in, outermost first. */
    public List<String> buildGroup = new ArrayList<>();
    public String artifact;
    public String packaging;
    /** Folders grouped into this component (decision 176); empty when it is one folder. */
    public List<String> parts = new ArrayList<>();

    @Override
    public String id() {
        return id;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = Fact.start(id, evidence);
        m.put("name", name);
        m.put("type", type.value());
        m.put("root", root);
        m.put("path", path);
        m.put("build_files", buildFiles);
        m.put("technology", technology);
        m.put("roles", roles);
        m.put("composition", composition);
        m.put("files", files);
        m.put("loc_code", locCode);
        m.put("entry_points", entryPoints);
        m.put("detected_by", detectedBy);
        m.put("build_group", buildGroup);
        m.put("artifact", artifact);
        m.put("packaging", packaging);
        m.put("parts", parts);
        return m;
    }

    public static Component fromMap(Map<String, Object> m) {
        Component c = new Component();
        c.id = (String) m.get("id");
        c.evidence = Evidence.fromMaps(m.get("evidence"));
        c.name = (String) m.get("name");
        c.type = ComponentType.of((String) m.getOrDefault("type", "unknown"));
        c.root = (String) m.get("root");
        c.path = (String) m.get("path");
        c.buildFiles = MapValues.strings(m.get("build_files"));
        c.technology = MapValues.strings(m.get("technology"));
        c.roles = MapValues.strings(m.get("roles"));
        c.composition = MapValues.counts(m.get("composition"));
        c.files = MapValues.integer(m.get("files"), 0);
        c.locCode = MapValues.integer(m.get("loc_code"), 0);
        c.entryPoints = MapValues.strings(m.get("entry_points"));
        c.detectedBy = (String) m.get("detected_by");
        c.buildGroup = MapValues.strings(m.get("build_group"));
        c.artifact = (String) m.get("artifact");
        c.packaging = (String) m.get("packaging");
        c.parts = MapValues.strings(m.get("parts"));
        return c;
    }
}
