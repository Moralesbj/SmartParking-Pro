package com.smartparking.modules.compliance.api;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.audit.AuditService;
import com.smartparking.common.tenancy.ScopeService;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class ComplianceController {
    private final JdbcClient db;private final ScopeService scopes;private final ScopedDatabase data;private final AuditService audit;
    public ComplianceController(JdbcClient db,ScopeService scopes,ScopedDatabase data,AuditService audit) { this.db=db;this.scopes=scopes;this.data=data;this.audit=audit; }
    @GetMapping("/audit-logs") public List<Map<String,Object>> audit(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        var s=scopes.supervisor();return db.sql("SELECT * FROM audit_logs WHERE organization_id=:org AND branch_id=:branch ORDER BY occurred_at DESC LIMIT :size OFFSET :offset")
            .param("org",s.organizationId()).param("branch",s.branchId()).param("size",Math.clamp(size,1,100)).param("offset",Math.max(0,page)*Math.clamp(size,1,100)).query().listOfRows();
    }
    @GetMapping("/anomaly-alerts") public List<Map<String,Object>> alerts(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return data.list(scopes.supervisor(),"anomaly_alerts",page,size); }
    @PostMapping("/anomaly-alerts/{id}/resolve") @Transactional public void resolve(@PathVariable UUID id) {
        var s=scopes.supervisor();data.row(s,"anomaly_alerts",id,true);
        db.sql("UPDATE anomaly_alerts SET status='RESOLVED',resolved_at=now(),resolved_by=:actor WHERE id=:id").param("actor",s.actorId()).param("id",id).update();audit.record(s,"ANOMALY_RESOLVED","anomaly_alerts",id);
    }
    @GetMapping("/notifications") public List<Map<String,Object>> notifications(@RequestParam(defaultValue="0") int page) {
        var s=scopes.current();return db.sql("SELECT * FROM notifications WHERE organization_id=:org AND recipient_user_id=:user ORDER BY created_at DESC LIMIT 20 OFFSET :offset")
            .param("org",s.organizationId()).param("user",s.actorId()).param("offset",Math.max(0,page)*20).query().listOfRows();
    }
    @PostMapping("/notifications/{id}/read") @Transactional public void read(@PathVariable UUID id) {
        var s=scopes.current();db.sql("UPDATE notifications SET read_at=coalesce(read_at,now()) WHERE id=:id AND organization_id=:org AND recipient_user_id=:user")
            .param("id",id).param("org",s.organizationId()).param("user",s.actorId()).update();
    }
}
