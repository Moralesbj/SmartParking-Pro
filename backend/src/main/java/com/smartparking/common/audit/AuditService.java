package com.smartparking.common.audit;

import com.smartparking.common.tenancy.Scope;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcClient db;
    public AuditService(JdbcClient db) { this.db = db; }
    public void record(Scope s, String action, String entity, UUID id) {
        db.sql("INSERT INTO audit_logs(id,organization_id,branch_id,actor_id,action,entity_type,entity_id) VALUES(:id,:org,:branch,:actor,:action,:entity,:ref)")
            .param("id",UUID.randomUUID()).param("org",s.organizationId()).param("branch",s.branchId()).param("actor",s.actorId())
            .param("action",action).param("entity",entity).param("ref",id).update();
    }
}
