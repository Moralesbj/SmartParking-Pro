package com.smartparking.modules.identity.api;

import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.tenancy.ScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class IdentityController {
    private final JdbcClient db; private final ScopeService scopes; private final PasswordEncoder encoder; private final AuditService audit;
    public IdentityController(JdbcClient db,ScopeService scopes,PasswordEncoder encoder,AuditService audit) { this.db=db;this.scopes=scopes;this.encoder=encoder;this.audit=audit; }
    public record NewUser(@Email @NotBlank String email,@NotBlank @Size(max=160) String fullName,@Size(min=8,max=72) String password,@Pattern(regexp="ADMIN|SUPERVISOR|OPERATOR|CUSTOMER") String role) {}
    @GetMapping public List<Map<String,Object>> list() {
        var s=scopes.admin();
        return db.sql("SELECT u.id,u.email,u.full_name,u.active,m.role,m.branch_id FROM users u JOIN user_memberships m ON m.user_id=u.id WHERE m.organization_id=:org ORDER BY u.full_name LIMIT 100")
            .param("org",s.organizationId()).query().listOfRows();
    }
    @PostMapping @Transactional public Map<String,UUID> create(@Valid @RequestBody NewUser body) {
        var s=scopes.admin(); UUID id=UUID.randomUUID();
        if(body.password()==null || body.role()==null) throw new ApiException(422,"Contraseña y rol requeridos");
        com.smartparking.modules.auth.application.AuthService.validatePassword(body.password());
        db.sql("INSERT INTO users(id,email,password_hash,full_name) VALUES(:id,:email,:hash,:name)").param("id",id).param("email",body.email().toLowerCase(Locale.ROOT)).param("hash",encoder.encode(body.password())).param("name",body.fullName()).update();
        db.sql("INSERT INTO user_memberships(id,user_id,organization_id,branch_id,role) VALUES(:id,:user,:org,:branch,:role)")
            .param("id",UUID.randomUUID()).param("user",id).param("org",s.organizationId()).param("branch",s.branchId()).param("role",body.role()).update();
        if(body.role().equals("CUSTOMER")) db.sql("INSERT INTO customers(id,organization_id,user_id,name,email) VALUES(:id,:org,:user,:name,:email)")
            .param("id",UUID.randomUUID()).param("org",s.organizationId()).param("user",id).param("name",body.fullName()).param("email",body.email().toLowerCase(Locale.ROOT)).update();
        audit.record(s,"USER_CREATED","users",id); return Map.of("id",id);
    }
    @PostMapping("/{id}/suspend") @Transactional public void suspend(@PathVariable UUID id) {
        var s=scopes.admin(); if(s.actorId().equals(id)) throw ApiException.conflict("No puede suspender su propio acceso");
        if(db.sql("UPDATE user_memberships SET active=false WHERE user_id=:id AND organization_id=:org AND role<>'SUPERADMIN'")
            .param("id",id).param("org",s.organizationId()).update()==0) throw ApiException.notFound();
        audit.record(s,"MEMBERSHIP_SUSPENDED","users",id);
    }
}
