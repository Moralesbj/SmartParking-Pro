package com.smartparking.security;

import com.smartparking.modules.auth.application.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Revokes a session when the user is deactivated or changes credentials after it was created.
 * Not a Spring bean on purpose: it is registered only inside the security chain.
 */
public class SessionValidityFilter extends OncePerRequestFilter {
    public static final String AUTH_AT = "SP_AUTHENTICATED_AT";
    private final AuthService auth;
    public SessionValidityFilter(AuthService auth) { this.auth = auth; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var session = req.getSession(false);
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken) && session != null) {
            Object at = session.getAttribute(AUTH_AT);
            boolean valid;
            try { valid = at instanceof Long epoch && auth.sessionValid(UUID.fromString(authentication.getName()), Instant.ofEpochMilli(epoch)); }
            catch (IllegalArgumentException e) { valid = false; }
            if (!valid) {
                session.invalidate();
                SecurityContextHolder.clearContext();
                res.setStatus(401);
                res.setCharacterEncoding("UTF-8"); res.setContentType("application/problem+json");
                res.getWriter().write("{\"status\":401,\"code\":\"SESSION_REVOKED\",\"detail\":\"La sesión fue revocada. Inicie sesión de nuevo\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
