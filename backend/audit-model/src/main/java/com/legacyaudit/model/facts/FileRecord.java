package com.legacyaudit.model.facts;

import java.util.List;
import java.util.Map;

/** One file of the inventory. {@code component} is set once components are known. */
public final class FileRecord implements Fact {

    public String id;
    public List<Evidence> evidence;
    /** Path relative to the source root, POSIX style (prefixed with the root name when there are several). */
    public String path;
    /** Name of the source root the file belongs to. */
    public String root;
    public Origin origin = Origin.SOURCE;
    public String component;
    public String language;
    public Category category;
    public long size;
    public String sha256;
    public String encoding;
    public int locCode;
    public int locComment;
    public int locBlank;

    @Override
    public String id() {
        return id;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = Fact.start(id, evidence);
        m.put("path", path);
        m.put("root", root);
        m.put("origin", origin.value());
        m.put("component", component);
        m.put("language", language);
        m.put("category", category.value());
        m.put("size", size);
        m.put("sha256", sha256);
        m.put("encoding", encoding);
        m.put("loc_code", locCode);
        m.put("loc_comment", locComment);
        m.put("loc_blank", locBlank);
        return m;
    }

    public static FileRecord fromMap(Map<String, Object> m) {
        FileRecord r = new FileRecord();
        r.id = (String) m.get("id");
        r.evidence = Evidence.fromMaps(m.get("evidence"));
        r.path = (String) m.get("path");
        r.root = (String) m.get("root");
        r.origin = Origin.of((String) m.getOrDefault("origin", "source"));
        r.component = (String) m.get("component");
        r.language = (String) m.get("language");
        r.category = Category.of((String) m.get("category"));
        r.size = MapValues.number(m.get("size"));
        r.sha256 = (String) m.get("sha256");
        r.encoding = (String) m.get("encoding");
        r.locCode = MapValues.integer(m.get("loc_code"), 0);
        r.locComment = MapValues.integer(m.get("loc_comment"), 0);
        r.locBlank = MapValues.integer(m.get("loc_blank"), 0);
        return r;
    }
}
