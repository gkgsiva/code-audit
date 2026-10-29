package com.legacyaudit.server.scans;

import java.time.OffsetDateTime;

/**
 * One scan started from the web app.
 *
 * @param status queued, running, done, failed or cancelled
 */
public record Scan(long id, String projectId, String status, boolean fullScan, String startedBy, OffsetDateTime queuedAt,
        OffsetDateTime startedAt, OffsetDateTime finishedAt, String runId, Integer files, Integer analyzed, Integer cached,
        Integer components, Long locCode, Integer warnings, String error) {

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    public boolean active() {
        return QUEUED.equals(status) || RUNNING.equals(status);
    }
}
