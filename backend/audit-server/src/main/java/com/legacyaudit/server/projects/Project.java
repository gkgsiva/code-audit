package com.legacyaudit.server.projects;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * A project: a folder under the audits root holding audit.yaml.
 *
 * @param members user name to project role (contributor or viewer)
 * @param hiddenReports reports the owner hid on this project (keys of {@code AdminSettings.REPORTS})
 * @param hiddenSections summary-report sections the owner hid on this project
 */
public record Project(String id, String title, String description, String owner, String sourcePath, boolean archived,
        OffsetDateTime createdAt, Map<String, String> members, List<String> hiddenReports, List<String> hiddenSections) {}
