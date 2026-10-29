package com.legacyaudit.model.facts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fact of the scan. Every fact has a stable {@code id} (a hash of its kind and key fields, so
 * ids survive re-runs) and {@code evidence} pointing at the file and line it came from.
 * {@link #toMap()} gives the fields in the order of the facts JSON Schema in {@code schemas/facts/},
 * which is also the order of the CSV columns.
 */
public interface Fact {

    String id();

    /** The fields by their JSON names, in schema order. */
    Map<String, Object> toMap();

    /** Deterministic id: {@code <kind>:<16 hex chars>} from the kind and key fields. */
    static String makeId(String kind, Object... parts) {
        StringBuilder raw = new StringBuilder(kind);
        for (Object p : parts) {
            raw.append('\u001f').append(text(p));
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            return kind + ":" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A value as the Python tool printed it into ids, so ids stay the same across both tools. */
    private static String text(Object p) {
        if (p == null) {
            return "None";
        }
        if (p instanceof Boolean b) {
            return b ? "True" : "False";
        }
        return p.toString();
    }

    static List<Object> evidenceMaps(List<Evidence> evidence) {
        List<Object> out = new ArrayList<>();
        for (Evidence e : evidence) {
            out.add(e.toMap());
        }
        return out;
    }

    static Map<String, Object> start(String id, List<Evidence> evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("evidence", evidenceMaps(evidence));
        return m;
    }
}
