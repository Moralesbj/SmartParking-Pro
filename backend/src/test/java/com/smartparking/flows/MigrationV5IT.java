package com.smartparking.flows;

import static org.assertj.core.api.Assertions.*;

import com.smartparking.support.PostgresSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V5 on a database that already holds data from the hourly model: categories are converted without losing information,
 * nothing is deleted, and the upgrade refuses to run while a stay is still open.
 */
class MigrationV5IT {
    private static final String LEGACY = """
        INSERT INTO organizations(id,name) VALUES('00000000-0000-0000-0000-0000000000a1','Legado');
        INSERT INTO branches(id,organization_id,code,name) VALUES('00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000a1','B1','Sede');
        INSERT INTO users(id,email,password_hash,full_name) VALUES('00000000-0000-0000-0000-0000000000c1','op@legado.local','x','Operador');
        INSERT INTO vehicles(id,organization_id,plate,type) VALUES
          ('00000000-0000-0000-0000-0000000000d1','00000000-0000-0000-0000-0000000000a1','EVV123','ELECTRIC'),
          ('00000000-0000-0000-0000-0000000000d2','00000000-0000-0000-0000-0000000000a1','MOT12A','MOTORCYCLE');
        INSERT INTO parking_spaces(id,organization_id,branch_id,code,type) VALUES
          ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b1','E-01','ELECTRIC'),
          ('00000000-0000-0000-0000-0000000000e2','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b1','A-01','ACCESSIBLE'),
          ('00000000-0000-0000-0000-0000000000e3','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b1','M-01','MOTORCYCLE');
        INSERT INTO tariff_plans(id,organization_id,branch_id,name,type,hourly_rate) VALUES
          ('00000000-0000-0000-0000-0000000000f1','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b1','Eléctrico','ELECTRIC',3500);
        INSERT INTO parking_sessions(id,organization_id,branch_id,vehicle_id,space_id,entry_operator_id,hourly_rate,grace_minutes,block_minutes,
                                     idempotency_key,status,entry_at,exit_at,final_amount)
          VALUES('00000000-0000-0000-0000-000000000101','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b1',
                 '00000000-0000-0000-0000-0000000000d1','00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000c1',3500,0,60,
                 gen_random_uuid(),'CLOSED',now()-interval '3 hours',now()-interval '1 hour',7000);
        INSERT INTO customers(id,organization_id,name) VALUES('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-0000000000a1','Cliente');
        UPDATE vehicles SET customer_id='00000000-0000-0000-0000-000000000201' WHERE id='00000000-0000-0000-0000-0000000000d2';
        INSERT INTO subscription_plans(id,organization_id,branch_id,name,price,duration_days) VALUES
          ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b1','Motos',90000,30);
        INSERT INTO subscriptions(id,organization_id,branch_id,customer_id,vehicle_id,plan_id,starts_at,ends_at,status,price) VALUES
          (gen_random_uuid(),'00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-000000000201',
           '00000000-0000-0000-0000-0000000000d2','00000000-0000-0000-0000-000000000301',now(),now()+interval '30 days','ACTIVE',90000);
        """;

    private static String database() throws Exception {
        var c = PostgresSupport.connection();
        String name = "mig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (var conn = DriverManager.getConnection(c.url(), c.user(), c.password()); var st = conn.createStatement()) { st.execute("CREATE DATABASE " + name); }
        return c.url().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    private static Flyway flyway(String url, String target) {
        var c = PostgresSupport.connection();
        return Flyway.configure().dataSource(url, c.user(), c.password()).locations("classpath:db/migration").target(target).cleanDisabled(true).load();
    }

    private static Connection connect(String url) throws Exception { var c = PostgresSupport.connection(); return DriverManager.getConnection(url, c.user(), c.password()); }
    private static Object one(Connection conn, String sql) throws Exception {
        try (var st = conn.createStatement(); var rs = st.executeQuery(sql)) { rs.next(); return rs.getObject(1); }
    }

    @Test
    void legacyDataIsConvertedAndNothingIsDeleted() throws Exception {
        String url = database();
        flyway(url, "4").migrate();
        try (var conn = connect(url); var st = conn.createStatement()) { st.execute(LEGACY); }
        flyway(url, "latest").migrate();
        try (var conn = connect(url)) {
            assertThat(one(conn, "SELECT type FROM vehicles WHERE plate='EVV123'")).isEqualTo("CAR");
            assertThat(one(conn, "SELECT type||'/'||feature FROM parking_spaces WHERE code='E-01'")).isEqualTo("CAR/EV_CHARGING");
            assertThat(one(conn, "SELECT type||'/'||feature FROM parking_spaces WHERE code='A-01'")).isEqualTo("CAR/ACCESSIBLE");
            assertThat(one(conn, "SELECT type||'/'||feature FROM parking_spaces WHERE code='M-01'")).isEqualTo("MOTORCYCLE/STANDARD");
            // The closed stay keeps its amount and is marked as priced by the old model.
            assertThat(one(conn, "SELECT pricing_model||'/'||vehicle_type||'/'||final_amount FROM parking_sessions")).isEqualTo("HOURLY_LEGACY/CAR/7000.00");
            // Old tariff rows are kept for history.
            assertThat(((Number) one(conn, "SELECT count(*) FROM tariff_plans")).longValue()).isEqualTo(1L);
            // The plan takes the category of its contracts; every branch gets the four category rates, versioned.
            assertThat(one(conn, "SELECT vehicle_type FROM subscription_plans")).isEqualTo("MOTORCYCLE");
            assertThat(((Number) one(conn, "SELECT count(*) FROM vehicle_rates")).longValue()).isEqualTo(4L);
            assertThat(((Number) one(conn, "SELECT count(*) FROM vehicle_rate_versions")).longValue()).isEqualTo(4L);
            assertThat(one(conn, "SELECT minute_rate||'/'||full_rate||'/'||block_minutes FROM vehicle_rates WHERE vehicle_type='CAR'")).isEqualTo("150.00/32000.00/720");
        }
    }

    @Test
    void upgradeRefusesToRunWhileAStayIsOpen() throws Exception {
        String url = database();
        flyway(url, "4").migrate();
        try (var conn = connect(url); var st = conn.createStatement()) {
            st.execute(LEGACY);
            st.execute("UPDATE parking_sessions SET status='OPEN', exit_at=NULL, final_amount=NULL");
        }
        assertThatThrownBy(() -> flyway(url, "latest").migrate()).hasStackTraceContaining("cierre las estancias abiertas");
        try (var conn = connect(url)) {
            // Rolled back: still at V4 with the original data.
            assertThat(((Number) one(conn, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).intValue()).isEqualTo(4);
            assertThat(one(conn, "SELECT type FROM vehicles WHERE plate='EVV123'")).isEqualTo("ELECTRIC");
            assertThat(one(conn, "SELECT status FROM parking_sessions")).isEqualTo("OPEN");
        }
    }
}
