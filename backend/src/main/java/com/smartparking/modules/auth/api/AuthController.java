package com.smartparking.modules.auth.api;

import com.smartparking.common.errors.ApiException;
import com.smartparking.modules.auth.application.AuthService;
import com.smartparking.security.RateLimiter;
import com.smartparking.security.SessionValidityFilter;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String MFA_PENDING_USER = "SP_MFA_PENDING_USER", MFA_PENDING_AT = "SP_MFA_PENDING_AT";
    private final JdbcClient db;
    private final AuthService auth;
    private final RateLimiter limiter;
    private final HttpSessionSecurityContextRepository contexts;
    private final int loginsPerIp;

    public AuthController(JdbcClient db, AuthService auth, RateLimiter limiter, HttpSessionSecurityContextRepository contexts,
                          @org.springframework.beans.factory.annotation.Value("${app.security.login-attempts-per-ip:30}") int loginsPerIp) {
        this.db = db; this.auth = auth; this.limiter = limiter; this.contexts = contexts; this.loginsPerIp = loginsPerIp;
    }

    public record Credentials(@Email @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}
    public record MfaCode(@Pattern(regexp = "\\d{6}") String code, @Size(max = 20) String recoveryCode) {}
    public record Enable(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}
    public record Forgot(@Email @NotBlank @Size(max = 254) String email) {}
    public record Reset(@NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 72) String newPassword) {}

    private static AuthService.Client client(HttpServletRequest r) { return new AuthService.Client(r.getRemoteAddr(), r.getHeader("User-Agent")); }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken(), "headerName", token.getHeaderName()); }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
        limiter.check("login-ip", request.getRemoteAddr(), loginsPerIp, 300);
        var result = auth.login(credentials.email(), credentials.password(), client(request));
        // Session fixation protection: always start from a fresh session id.
        var old = request.getSession(false);
        if (old != null) old.invalidate();
        var session = request.getSession(true);
        if (result.mfaRequired()) {
            session.setAttribute(MFA_PENDING_USER, result.userId().toString());
            session.setAttribute(MFA_PENDING_AT, Instant.now().toEpochMilli());
            return Map.of("mfaRequired", true);
        }
        authenticate(result.userId(), request, response);
        return me();
    }

    @PostMapping("/mfa/verify")
    public Map<String, Object> verifyMfa(@Valid @RequestBody MfaCode body, HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null || session.getAttribute(MFA_PENDING_USER) == null) throw new ApiException(401, "Inicie sesión primero");
        long started = (Long) session.getAttribute(MFA_PENDING_AT);
        if (Instant.ofEpochMilli(started).plusSeconds(300).isBefore(Instant.now())) { session.invalidate(); throw new ApiException(401, "El paso de verificación venció; inicie sesión de nuevo"); }
        UUID user = UUID.fromString((String) session.getAttribute(MFA_PENDING_USER));
        limiter.check("mfa", user.toString(), 5, 300);
        auth.verifyMfa(user, body.code(), body.recoveryCode(), client(request));
        session.invalidate();
        request.getSession(true);
        authenticate(user, request, response);
        return me();
    }

    private void authenticate(UUID user, HttpServletRequest request, HttpServletResponse response) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        request.getSession().setAttribute(SessionValidityFilter.AUTH_AT, auth.databaseNow().toEpochMilli());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated()) auth.logout(UUID.fromString(a.getName()), client(request));
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> forgot(@Valid @RequestBody Forgot body, HttpServletRequest request) {
        limiter.check("forgot-ip", request.getRemoteAddr(), 5, 900);
        limiter.check("forgot-email", body.email().toLowerCase(Locale.ROOT), 3, 900);
        auth.forgotPassword(body.email(), client(request));
        return Map.of("detail", "Si la cuenta existe, se enviarán instrucciones al correo registrado");
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody Reset body, HttpServletRequest request) {
        limiter.check("reset-ip", request.getRemoteAddr(), 10, 900);
        auth.resetPassword(body.token(), body.newPassword(), client(request));
    }

    @PostMapping("/mfa/setup")
    public Map<String, String> setupMfa() { return auth.setupMfa(currentUser()); }

    @PostMapping("/mfa/enable")
    public Map<String, Object> enableMfa(@Valid @RequestBody Enable body, HttpServletRequest request) {
        return Map.of("recoveryCodes", auth.enableMfa(currentUser(), body.code(), client(request)),
            "detail", "Guarde los códigos de recuperación; no se volverán a mostrar");
    }

    private static UUID currentUser() { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); }

    @GetMapping("/me")
    public Map<String, Object> me() {
        UUID id = currentUser();
        var user = db.sql("SELECT id,email,full_name,mfa_enabled FROM users WHERE id=:id AND active").param("id", id).query().listOfRows().stream().findFirst()
            .orElseThrow(() -> new ApiException(401, "Cuenta inactiva"));
        var scopes = db.sql("""
            SELECT o.id organization_id,o.name organization_name,b.id branch_id,b.name branch_name,b.timezone,
                   (array_agg(m.role ORDER BY CASE m.role WHEN 'SUPERADMIN' THEN 0 WHEN 'ADMIN' THEN 1 WHEN 'SUPERVISOR' THEN 2 WHEN 'OPERATOR' THEN 3 ELSE 4 END))[1] role
            FROM user_memberships m JOIN organizations o ON o.id=m.organization_id AND o.active
            JOIN branches b ON b.organization_id=o.id AND (m.branch_id IS NULL OR b.id=m.branch_id)
            WHERE m.user_id=:id AND m.active AND b.active
            GROUP BY o.id,o.name,b.id,b.name,b.timezone ORDER BY o.name,b.name
            """).param("id", id).query().listOfRows();
        var shown = new LinkedHashMap<>(user);
        shown.put("mfa_enabled", auth.mfaAvailable() && Boolean.TRUE.equals(user.get("mfa_enabled")));
        return Map.of("user", shown, "scopes", scopes, "mfaAvailable", auth.mfaAvailable());
    }
}
