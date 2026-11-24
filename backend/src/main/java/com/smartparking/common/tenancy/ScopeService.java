package com.smartparking.common.tenancy;

import com.smartparking.common.errors.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Resolves the caller's scope. The client only *selects* an organization/branch via headers;
 * the role is always re-read from active memberships in PostgreSQL on every request.
 */
@Service
public class ScopeService {
    private final JdbcClient db;
    private final HttpServletRequest request;
    private final boolean requireMfaForPrivileged;

    public ScopeService(JdbcClient db, HttpServletRequest request, @Value("${app.security.require-mfa-privileged:true}") boolean requireMfaForPrivileged,
                        @Value("${app.security.mfa-enabled:false}") boolean mfaFeature) {
        this.db = db; this.request = request; this.requireMfaForPrivileged = mfaFeature && requireMfaForPrivileged;
    }

    public UUID actor() { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); }

    public Scope current() {
        UUID actor = actor();
        String org = request.getHeader("X-Organization-Id");
        String branch = request.getHeader("X-Branch-Id");
        if (org == null || branch == null) throw new ApiException(422, "Seleccione organización y sede", "SCOPE_REQUIRED");
        UUID organizationId, branchId;
        try { organizationId = UUID.fromString(org); branchId = UUID.fromString(branch); }
        catch (IllegalArgumentException e) { throw new ApiException(422, "Identificador de alcance inválido", "SCOPE_REQUIRED"); }
        var row = db.sql("""
            SELECT m.role, u.mfa_enabled FROM user_memberships m JOIN users u ON u.id=m.user_id
            JOIN organizations o ON o.id=m.organization_id
            JOIN branches b ON b.organization_id=m.organization_id
            WHERE m.user_id=:actor AND m.organization_id=:org AND b.id=:branch
              AND (m.branch_id IS NULL OR m.branch_id=b.id) AND m.active AND u.active AND b.active AND o.active
            ORDER BY CASE m.role WHEN 'SUPERADMIN' THEN 0 WHEN 'ADMIN' THEN 1 WHEN 'SUPERVISOR' THEN 2 WHEN 'OPERATOR' THEN 3 ELSE 4 END LIMIT 1
            """).param("actor", actor).param("org", organizationId).param("branch", branchId).query().listOfRows().stream().findFirst()
            .orElseThrow(() -> new ApiException(403, "No tiene acceso a esta sede", "SCOPE_DENIED"));
        String role = (String) row.get("role");
        if (requireMfaForPrivileged && (role.equals("SUPERADMIN") || role.equals("ADMIN")) && !Boolean.TRUE.equals(row.get("mfa_enabled")))
            throw new ApiException(403, "Su rol requiere verificación en dos pasos. Actívela en el menú Seguridad", "MFA_ENROLLMENT_REQUIRED");
        return new Scope(organizationId, branchId, actor, role);
    }

    public Scope staff() { var s = current(); if (s.role().equals("CUSTOMER")) throw new ApiException(403, "Acceso administrativo restringido"); return s; }
    public Scope admin() { var s = staff(); if (!s.administrator()) throw new ApiException(403, "Se requiere administración"); return s; }
    public Scope supervisor() { var s = staff(); if (!s.supervisor()) throw new ApiException(403, "Se requiere supervisión"); return s; }
    public Scope superadmin() { var s = staff(); if (!s.role().equals("SUPERADMIN")) throw new ApiException(403, "Se requiere superadministración"); return s; }
}
