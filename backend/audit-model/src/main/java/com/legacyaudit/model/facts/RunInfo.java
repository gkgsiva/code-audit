package com.legacyaudit.model.facts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Describes one scan run; written as {@code run.json}. Not part of the compared facts. */
public final class RunInfo {

    public String runId;
    public String toolVersion;
    public String startedAt;
    public String finishedAt;
    public String configPath;
    public String configHash;
    public String application;
    public List<SourceInfo> sources = new ArrayList<>();
    public boolean fullScan;
    public int filesTotal;
    public int filesAnalyzed;
    public int filesCached;
    public int filesRemoved;
    public Map<String, Double> timings = new LinkedHashMap<>();
    public List<InputCoverage> coverage = new ArrayList<>();
    public Map<String, Object> overrides = new LinkedHashMap<>();
    public Map<String, String> countingRules = new LinkedHashMap<>();
    public List<String> warnings = new ArrayList<>();
    public Map<String, Object> analyzers = new LinkedHashMap<>();
    public int unresolvedReferences;
    public List<Object> vulnerabilitySources = new ArrayList<>();
    public Map<String, Object> target = new LinkedHashMap<>();

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", runId);
        m.put("tool_version", toolVersion);
        m.put("started_at", startedAt);
        m.put("finished_at", finishedAt);
        m.put("config_path", configPath);
        m.put("config_hash", configHash);
        m.put("application", application);
        m.put("sources", sources.stream().map(SourceInfo::toMap).toList());
        m.put("full_scan", fullScan);
        m.put("files_total", filesTotal);
        m.put("files_analyzed", filesAnalyzed);
        m.put("files_cached", filesCached);
        m.put("files_removed", filesRemoved);
        m.put("timings", timings);
        m.put("coverage", coverage.stream().map(InputCoverage::toMap).toList());
        m.put("overrides", overrides);
        m.put("counting_rules", countingRules);
        m.put("warnings", warnings);
        m.put("analyzers", analyzers);
        m.put("unresolved_references", unresolvedReferences);
        m.put("vulnerability_sources", vulnerabilitySources);
        m.put("target", target);
        return m;
    }
}
