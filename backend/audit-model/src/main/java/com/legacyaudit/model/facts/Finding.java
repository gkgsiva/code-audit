package com.legacyaudit.model.facts;

import java.util.List;
import java.util.Map;

/** Something the scan wants a person to look at: a coverage gap, a risk, a rule hit. */
public record Finding(
        String id,
        List<Evidence> evidence,
        String ruleId,
        Severity severity,
        String category,
        String file,
        Integer line,
        String message,
        String remediation)
        implements Fact {

    /** A finding about one file (or folder), with that file as its evidence. */
    public static Finding about(String ruleId, Severity severity, String category, String file, String message, Object... idParts) {
        Object[] parts = new Object[idParts.length + 1];
        parts[0] = ruleId;
        System.arraycopy(idParts, 0, parts, 1, idParts.length);
        return new Finding(Fact.makeId("finding", parts), Evidence.list(file), ruleId, severity, category, file, null, message, null);
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = Fact.start(id, evidence);
        m.put("rule_id", ruleId);
        m.put("severity", severity.value());
        m.put("category", category);
        m.put("file", file);
        m.put("line", line);
        m.put("message", message);
        m.put("remediation", remediation);
        return m;
    }
}
