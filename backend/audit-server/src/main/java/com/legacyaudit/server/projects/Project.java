package com.legacyaudit.server.projects;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A project: a folder under the audits root holding audit.yaml.
 *
 * @param members user name to project role (contributor or viewer)
 */
public record Project(String id, String title, String description, String owner, String sourcePath, boolean archived,
        OffsetDateTime createdAt, Map<String, String> members) {}
