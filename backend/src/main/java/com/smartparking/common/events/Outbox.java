package com.smartparking.common.events;

import com.smartparking.common.tenancy.Scope;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Transactional outbox: events commit atomically with the business change that produced them. */
@Service
public class Outbox {
    private final JdbcClient db;
    public Outbox(JdbcClient db) { this.db = db; }

    public void emit(Scope scope, String type, UUID aggregate) { emitSystem(scope.organizationId(), scope.branchId(), type, aggregate); }

    /** For scheduled jobs that act on behalf of the system rather than a user. */
    public void emitSystem(UUID organization, UUID branch, String type, UUID aggregate) {
        db.sql("INSERT INTO outbox_events(id,organization_id,branch_id,event_type,aggregate_id) VALUES(:id,:org,:branch,:type,:aggregate)")
            .param("id", UUID.randomUUID()).param("org", organization).param("branch", branch).param("type", type).param("aggregate", aggregate).update();
    }
}
