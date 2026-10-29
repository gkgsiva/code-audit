package com.legacyaudit.server.projects;

import com.legacyaudit.server.auth.User;
import java.util.List;

/** Roles from low to high: viewer, contributor, owner, admin (as in the Python hosted site). */
public final class Roles {

    public static final String VIEWER = "viewer";
    public static final String CONTRIBUTOR = "contributor";
    public static final String OWNER = "owner";
    public static final String ADMIN = "admin";

    /** Roles a project member can have; the owner is kept on the project itself. */
    public static final List<String> MEMBER_ROLES = List.of(CONTRIBUTOR, VIEWER);

    private static final List<String> ORDER = List.of(VIEWER, CONTRIBUTOR, OWNER, ADMIN);

    private Roles() {}

    public static int rank(String role) {
        return ORDER.indexOf(role) + 1;
    }

    public static boolean atLeast(String role, String needed) {
        return role != null && rank(role) >= rank(needed);
    }

    /** The user's role on a project, or null when the user may not see it. */
    public static String on(User user, Project project) {
        if (ADMIN.equals(user.role())) {
            return ADMIN;
        }
        if (user.name().equals(project.owner())) {
            return OWNER;
        }
        String role = project.members().get(user.name());
        if (role == null) {
            return null;
        }
        // a global viewer stays a viewer whatever a project says
        return VIEWER.equals(user.role()) ? VIEWER : role;
    }

    /** Admins and owners create projects; contributors and viewers are added to them. */
    public static boolean canCreate(User user) {
        return ADMIN.equals(user.role()) || OWNER.equals(user.role());
    }
}
