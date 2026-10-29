package com.legacyaudit.server.auth;

import com.legacyaudit.server.web.ApiException;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** The logged-in user behind a request, read fresh so a role change or a disable counts at once. */
@Component
public class CurrentUser {

    private final UserStore users;

    public CurrentUser(UserStore users) {
        this.users = users;
    }

    public User of(Principal principal) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Log in first");
        }
        User user = users.find(principal.getName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Log in first"));
        if (user.disabled() && !User.ADMIN.equals(user.role())) {
            throw ApiException.forbidden("This user is disabled; ask an admin");
        }
        return user;
    }
}
