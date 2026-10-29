package com.legacyaudit.model.facts;

import java.util.List;
import java.util.Map;

/** Whether one kind of supplementary input ({@code audit-inputs/<kind>/}) was provided. */
public record InputCoverage(
        String id, List<Evidence> evidence, String kind, String label, CoverageStatus status, String path, int fileCount)
        implements Fact {

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = Fact.start(id, evidence);
        m.put("kind", kind);
        m.put("label", label);
        m.put("status", status.value());
        m.put("path", path);
        m.put("file_count", fileCount);
        return m;
    }

    /** One line for the scan log. */
    public String line() {
        return status == CoverageStatus.PRESENT ? label + ": " + fileCount + " file(s)" : label + ": not provided";
    }
}
