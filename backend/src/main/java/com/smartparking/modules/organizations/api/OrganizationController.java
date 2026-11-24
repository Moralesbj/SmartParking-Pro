package com.smartparking.modules.organizations.api;

import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.tenancy.ScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/organizations")
public class OrganizationController {
    private final JdbcClient db;private final ScopeService scopes;private final AuditService audit;
    public OrganizationController(JdbcClient db,ScopeService scopes,AuditService audit) { this.db=db;this.scopes=scopes;this.audit=audit; }
    public record Organization(@NotBlank @Size(max=160) String name,@Size(max=40) String taxId) {}
    @GetMapping public List<Map<String,Object>> list() {
        return db.sql("SELECT DISTINCT o.* FROM organizations o JOIN user_memberships m ON m.organization_id=o.id WHERE m.user_id=:user AND m.active ORDER BY o.name LIMIT 100").param("user",scopes.actor()).query().listOfRows();
    }
    @PostMapping @Transactional public Map<String,UUID> create(@Valid @RequestBody Organization b) {
        var s=scopes.admin();if(!s.role().equals("SUPERADMIN")) throw new ApiException(403,"Se requiere superadministración");
        UUID id=UUID.randomUUID(),branch=UUID.randomUUID();
        db.sql("INSERT INTO organizations(id,name,tax_id) VALUES(:id,:name,:tax)").param("id",id).param("name",b.name()).param("tax",b.taxId()).update();
        db.sql("INSERT INTO branches(id,organization_id,code,name) VALUES(:id,:org,'PRINCIPAL','Sede principal')").param("id",branch).param("org",id).update();
        db.sql("INSERT INTO user_memberships(id,user_id,organization_id,role) VALUES(:id,:user,:org,'SUPERADMIN')").param("id",UUID.randomUUID()).param("user",s.actorId()).param("org",id).update();audit.record(s,"ORGANIZATION_CREATED","organizations",id);return Map.of("id",id,"branchId",branch);
    }
}
