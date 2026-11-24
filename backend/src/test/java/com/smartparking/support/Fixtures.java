package com.smartparking.support;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Builds isolated tenants per test. Each call uses fresh identifiers so tests never share data. */
public final class Fixtures {
    public record Tenant(UUID organizationId, UUID branchId) {}
    public record Actor(UUID userId, String email, String role, UUID organizationId, UUID branchId) {}
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static String hash;
    private final JdbcClient db;

    Fixtures(JdbcClient db, PasswordEncoder encoder) {
        this.db = db;
        synchronized (Fixtures.class) { if (hash == null) hash = encoder.encode(IntegrationTest.PASSWORD); }
    }

    public Tenant tenant() {
        UUID org = UUID.randomUUID(), branch = UUID.randomUUID();
        db.sql("INSERT INTO organizations(id,name) VALUES(:id,:name)").param("id", org).param("name", "Org " + SEQ.incrementAndGet()).update();
        db.sql("INSERT INTO branches(id,organization_id,code,name) VALUES(:id,:org,'B1','Sede 1')").param("id", branch).param("org", org).update();
        return new Tenant(org, branch);
    }

    public UUID branch(Tenant t, String code) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO branches(id,organization_id,code,name) VALUES(:id,:org,:code,:code)").param("id", id).param("org", t.organizationId()).param("code", code).update();
        return id;
    }

    /** role scoped to the tenant branch; orgWide=true leaves branch_id NULL (all branches). */
    public Actor user(Tenant t, String role, boolean orgWide) {
        UUID id = UUID.randomUUID();
        String email = role.toLowerCase() + SEQ.incrementAndGet() + "-" + id.toString().substring(0, 8) + "@prueba.local";
        db.sql("INSERT INTO users(id,email,password_hash,full_name) VALUES(:id,:email,:hash,:name)")
            .param("id", id).param("email", email).param("hash", hash).param("name", role + " de prueba").update();
        db.sql("INSERT INTO user_memberships(id,user_id,organization_id,branch_id,role) VALUES(:id,:user,:org,:branch,:role)")
            .param("id", UUID.randomUUID()).param("user", id).param("org", t.organizationId())
            .param("branch", orgWide ? null : t.branchId()).param("role", role).update();
        if (role.equals("CUSTOMER")) {
            db.sql("INSERT INTO customers(id,organization_id,user_id,name,email,consent) VALUES(:id,:org,:user,:name,:email,true)")
                .param("id", UUID.randomUUID()).param("org", t.organizationId()).param("user", id).param("name", "Cliente " + id.toString().substring(0, 6)).param("email", email).update();
        }
        return new Actor(id, email, role, t.organizationId(), t.branchId());
    }

    public Actor user(Tenant t, String role) { return user(t, role, role.equals("ADMIN")); }

    public UUID customerOf(Actor customer) {
        return db.sql("SELECT id FROM customers WHERE user_id=:u").param("u", customer.userId()).query(UUID.class).single();
    }

    public UUID customer(Tenant t) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO customers(id,organization_id,name,document_number,consent) VALUES(:id,:org,'Cliente','123',true)").param("id", id).param("org", t.organizationId()).update();
        return id;
    }

    private static final String LETTERS = "ABCDEFGHJKLMNPRSTUVWXYZ";
    /** Unique valid Colombian car plate (AAA999). */
    public static String plate() {
        int n = SEQ.incrementAndGet();
        return "" + LETTERS.charAt((n / 1000 / 23 / 23) % 23) + LETTERS.charAt((n / 1000 / 23) % 23) + LETTERS.charAt((n / 1000) % 23) + String.format("%03d", n % 1000);
    }

    public UUID vehicle(Tenant t, UUID customer, String type) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO vehicles(id,organization_id,customer_id,plate,type) VALUES(:id,:org,:customer,:plate,:type)")
            .param("id", id).param("org", t.organizationId()).param("customer", customer).param("plate", plate()).param("type", type).update();
        return id;
    }

    public UUID zone(Tenant t) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO parking_zones(id,organization_id,branch_id,name) VALUES(:id,:org,:branch,'Zona A')").param("id", id).param("org", t.organizationId()).param("branch", t.branchId()).update();
        return id;
    }

    public UUID space(Tenant t, String code, String type) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO parking_spaces(id,organization_id,branch_id,code,type) VALUES(:id,:org,:branch,:code,:type)")
            .param("id", id).param("org", t.organizationId()).param("branch", t.branchId()).param("code", code).param("type", type).update();
        return id;
    }

    /** Every branch is provisioned with the template rates and capacity 0; tests declare the capacity they need. */
    public void capacity(Tenant t, String type, int capacity) { capacity(t.branchId(), type, capacity); }

    public void capacity(UUID branch, String type, int capacity) {
        db.sql("UPDATE vehicle_rates SET capacity=:c WHERE branch_id=:b AND vehicle_type=:t").param("c", capacity).param("b", branch).param("t", type).update();
    }

    public void rate(Tenant t, String type, String minute, String full, int block, int grace) {
        db.sql("UPDATE vehicle_rates SET minute_rate=:m,full_rate=:f,block_minutes=:b,grace_minutes=:g WHERE branch_id=:branch AND vehicle_type=:t")
            .param("m", new BigDecimal(minute)).param("f", new BigDecimal(full)).param("b", block).param("g", grace).param("branch", t.branchId()).param("t", type).update();
    }

    public UUID plan(Tenant t, String type, String price, int days) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO subscription_plans(id,organization_id,branch_id,name,vehicle_type,price,duration_days) VALUES(:id,:o,:b,'Mensual',:type,:price,:days)")
            .param("id", id).param("o", t.organizationId()).param("b", t.branchId()).param("type", type).param("price", price == null ? null : new BigDecimal(price)).param("days", days).update();
        return id;
    }
}
