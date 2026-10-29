package com.legacyaudit.server.auth;

import com.legacyaudit.server.ServerSettings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test login, as in the Python hosted site: a user name and no password. A new name becomes an
 * owner; names listed in LEGACY_AUDIT_ADMINS are always admins. Okta replaces it later.
 */
@RestController
public class AuthController {

    /** Same rule as the Python hosted site (registry.USER_NAME). */
    static final Pattern USER_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._@-]{0,80}$");

    static final String BANNER = "Test login: anyone can log in with any name. Not for production; use Okta.";

    private final ServerSettings settings;
    private final UserStore users;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public AuthController(ServerSettings settings, UserStore users) {
        this.settings = settings;
        this.users = users;
    }

    public record LoginBody(String name) {}

    public record Me(String name, String role) {}

    @GetMapping("/api/auth/info")
    public Map<String, Object> info() {
        return Map.of("provider", settings.auth(), "label", "Test login", "loginForm", true, "banner", BANNER);
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginBody body, HttpServletRequest request, HttpServletResponse response) {
        String name = body == null || body.name() == null ? "" : body.name().strip();
        if (name.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "Type a user name");
        }
        if (!USER_NAME.matcher(name).matches()) {
            return error(HttpStatus.BAD_REQUEST, "Use letters, digits and . _ @ - only, starting with a letter or digit");
        }
        var existing = users.find(name);
        if (existing.isPresent() && existing.get().disabled() && !settings.admins().contains(name)) {
            return error(HttpStatus.FORBIDDEN, "This user is disabled; ask an admin");
        }
        String role = settings.admins().contains(name)
                ? User.ADMIN
                : existing.map(User::role).orElse(User.OWNER);
        User user = users.recordLogin(name, role);

        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                user.name(), null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().toUpperCase())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.ok(new Me(user.name(), user.role()));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/me")
    public Me me(Principal principal) {
        User user = users.find(principal.getName()).orElseThrow();
        return new Me(user.name(), user.role());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("detail", message));
    }
}
