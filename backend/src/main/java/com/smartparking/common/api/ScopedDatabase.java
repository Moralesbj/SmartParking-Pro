package com.smartparking.common.api;

import com.smartparking.common.errors.ApiException;
import com.smartparking.common.tenancy.Scope;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ScopedDatabase {
    private final JdbcClient db;
    public ScopedDatabase(JdbcClient db) { this.db=db; }
    public Map<String,Object> row(Scope s,String table,UUID id,boolean lock) {
        // Table names originate only in source, never from a request.
        return db.sql("SELECT * FROM "+table+" WHERE id=:id AND organization_id=:org AND branch_id=:branch"+(lock?" FOR UPDATE":""))
            .param("id",id).param("org",s.organizationId()).param("branch",s.branchId()).query().listOfRows().stream().findFirst().orElseThrow(ApiException::notFound);
    }
    public void lockBranch(Scope s) {
        db.sql("SELECT id FROM branches WHERE id=:branch AND organization_id=:org FOR UPDATE").param("branch",s.branchId()).param("org",s.organizationId()).query(UUID.class).single();
    }
    /** Business time zone of the scope's branch. */
    public java.time.ZoneId zone(Scope s) {
        return java.time.ZoneId.of(db.sql("SELECT timezone FROM branches WHERE id=:branch AND organization_id=:org").param("branch",s.branchId()).param("org",s.organizationId()).query(String.class).single());
    }
    public List<Map<String,Object>> list(Scope s,String table,int page,int size) {
        int limit=Math.clamp(size,1,100);
        return db.sql("SELECT * FROM "+table+" WHERE organization_id=:org AND branch_id=:branch ORDER BY 1 LIMIT :limit OFFSET :offset")
            .param("org",s.organizationId()).param("branch",s.branchId()).param("limit",limit).param("offset",Math.max(0,page)*limit).query().listOfRows();
    }
}
