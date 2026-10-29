package com.legacyaudit.server.auth;

import java.time.OffsetDateTime;

/** A person who can log in. Roles as in the Python hosted site: admin, owner, contributor, viewer. */
public record User(String name, String role, boolean disabled, OffsetDateTime createdAt, OffsetDateTime lastLogin) {

    public static final String ADMIN = "admin";
    public static final String OWNER = "owner";
}
