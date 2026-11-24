package com.smartparking.flows;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.common.tenancy.Scope;
import com.smartparking.modules.billing.application.BillingService;
import com.smartparking.modules.parking.application.ParkingService;
import com.smartparking.modules.pricing.application.VehicleRateService;
import com.smartparking.modules.reservations.application.ReservationService;
import com.smartparking.support.Fixtures;
import com.smartparking.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Flow F: concurrent check-in, last-space reservations and double payment against real PostgreSQL. */
class ConcurrencyIT extends IntegrationTest {
    @Autowired ParkingService parking;
    @Autowired BillingService billing;
    @Autowired ReservationService reservations;
    @Autowired VehicleRateService rates;

    @SuppressWarnings("unchecked")
    private static UUID orderOf(java.util.Map<String, Object> liquidation) { return (UUID) ((java.util.Map<String, Object>) liquidation.get("order")).get("id"); }

    private int race(int threads, Callable<?> task) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) futures.add(pool.submit(() -> { start.await(); try { task.call(); ok.incrementAndGet(); } catch (Exception ignored) { } return null; }));
        start.countDown();
        for (var f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        return ok.get();
    }

    private Scope scope(Fixtures.Tenant t, Fixtures.Actor a) { return new Scope(t.organizationId(), t.branchId(), a.userId(), a.role()); }

    @Test
    void sameSpaceCannotBeOccupiedTwice() throws Exception {
        var t = fx.tenant();
        var op = scope(t, fx.user(t, "OPERATOR"));
        fx.capacity(t, "CAR", 20);
        UUID space = fx.space(t, "C1", "CAR");
        List<UUID> vehicles = new ArrayList<>();
        for (int i = 0; i < 8; i++) vehicles.add(fx.vehicle(t, null, "CAR"));
        var idx = new AtomicInteger();
        int ok = race(8, () -> parking.checkIn(op, vehicles.get(idx.getAndIncrement()), space, UUID.randomUUID(), null));
        assertThat(ok).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM parking_sessions WHERE space_id=:s AND status='OPEN'").param("s", space).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void sameVehicleCannotHaveTwoOpenSessions() throws Exception {
        var t = fx.tenant();
        var op = scope(t, fx.user(t, "OPERATOR"));
        fx.capacity(t, "CAR", 20);
        UUID vehicle = fx.vehicle(t, null, "CAR");
        List<UUID> spaces = new ArrayList<>();
        for (int i = 0; i < 6; i++) spaces.add(fx.space(t, "V" + i, "CAR"));
        var idx = new AtomicInteger();
        int ok = race(6, () -> parking.checkIn(op, vehicle, spaces.get(idx.getAndIncrement()), UUID.randomUUID(), null));
        assertThat(ok).isEqualTo(1);
    }

    @Test
    void doublePaymentWithDifferentKeysNeverExceedsBalance() throws Exception {
        var t = fx.tenant();
        var actor = fx.user(t, "OPERATOR");
        var op = scope(t, actor);
        fx.capacity(t, "CAR", 20);
        UUID session = (UUID) parking.checkIn(op, fx.vehicle(t, null, "CAR"), fx.space(t, "P1", "CAR"), UUID.randomUUID(), null).get("id");
        db.sql("UPDATE parking_sessions SET entry_at=now()-interval '20 minutes'+interval '30 seconds' WHERE id=:id").param("id", session).update();
        UUID order = orderOf(parking.quote(op, session));
        UUID cash = UUID.randomUUID();
        db.sql("INSERT INTO cash_sessions(id,organization_id,branch_id,operator_id,opening_balance) VALUES(:id,:o,:b,:u,0)")
            .param("id", cash).param("o", t.organizationId()).param("b", t.branchId()).param("u", actor.userId()).update();
        int ok = race(6, () -> billing.capture(op, order, cash, "CASH", new BigDecimal("3000"), null, UUID.randomUUID()));
        assertThat(ok).isEqualTo(1);
        assertThat(db.sql("SELECT sum(amount) FROM payments WHERE order_id=:o").param("o", order).query(BigDecimal.class).single()).isEqualByComparingTo("3000"); // 20 min × 150
    }

    @Test
    void doubleClickSameIdempotencyKeyCreatesOnePayment() throws Exception {
        var t = fx.tenant();
        var actor = fx.user(t, "OPERATOR");
        var op = scope(t, actor);
        fx.capacity(t, "CAR", 20);
        UUID session = (UUID) parking.checkIn(op, fx.vehicle(t, null, "CAR"), fx.space(t, "P2", "CAR"), UUID.randomUUID(), null).get("id");
        db.sql("UPDATE parking_sessions SET entry_at=now()-interval '20 minutes'+interval '30 seconds' WHERE id=:id").param("id", session).update();
        UUID order = orderOf(parking.quote(op, session));
        UUID cash = UUID.randomUUID();
        db.sql("INSERT INTO cash_sessions(id,organization_id,branch_id,operator_id,opening_balance) VALUES(:id,:o,:b,:u,0)")
            .param("id", cash).param("o", t.organizationId()).param("b", t.branchId()).param("u", actor.userId()).update();
        UUID key = UUID.randomUUID();
        int ok = race(5, () -> billing.capture(op, order, cash, "CASH", new BigDecimal("3000"), null, key));
        assertThat(ok).isEqualTo(5); // every retry gets the same result
        assertThat(db.sql("SELECT count(*) FROM payments WHERE order_id=:o").param("o", order).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void categoryCapacityIsNeverExceededByConcurrentEntriesWithoutSpace() throws Exception {
        var t = fx.tenant();
        var op = scope(t, fx.user(t, "OPERATOR"));
        fx.capacity(t, "MOTORCYCLE", 3);
        List<UUID> motos = new ArrayList<>();
        for (int i = 0; i < 8; i++) motos.add(fx.vehicle(t, null, "MOTORCYCLE"));
        var idx = new AtomicInteger();
        int ok = race(8, () -> parking.checkIn(op, motos.get(idx.getAndIncrement()), null, UUID.randomUUID(), null));
        assertThat(ok).isEqualTo(3);
        assertThat(db.sql("SELECT count(*) FROM parking_sessions WHERE branch_id=:b AND status='OPEN' AND vehicle_type='MOTORCYCLE'").param("b", t.branchId()).query(Long.class).single()).isEqualTo(3L);
        // Ticket codes stay unique among open stays.
        assertThat(db.sql("SELECT count(DISTINCT ticket_code) FROM parking_sessions WHERE branch_id=:b AND status='OPEN'").param("b", t.branchId()).query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void rateChangesDuringConcurrentEntriesLeaveEachStayWithOneWholeVersion() throws Exception {
        var t = fx.tenant();
        var op = scope(t, fx.user(t, "OPERATOR"));
        var adminActor = fx.user(t, "ADMIN");
        var admin = scope(t, adminActor);
        fx.capacity(t, "CAR", 50);
        List<UUID> cars = new ArrayList<>();
        for (int i = 0; i < 6; i++) cars.add(fx.vehicle(t, null, "CAR"));
        var idx = new AtomicInteger();
        var step = new AtomicInteger();
        int ok = race(10, () -> {
            int n = idx.getAndIncrement();
            if (n < 6) return parking.checkIn(op, cars.get(n), null, UUID.randomUUID(), null);
            int k = step.incrementAndGet();
            return rates.update(admin, "CAR", new VehicleRateService.Update(new BigDecimal(150 + k * 10), new BigDecimal(32000 + k * 1000), 720, new BigDecimal("180000"), 0, 50, true));
        });
        assertThat(ok).isEqualTo(10);
        assertThat(db.sql("SELECT max(version) FROM vehicle_rate_versions WHERE branch_id=:b AND vehicle_type='CAR'").param("b", t.branchId()).query(Integer.class).single()).isEqualTo(6);
        // Each stay's minute rate and full rate are exactly those of the version it recorded: never a mix of two versions.
        long consistent = db.sql("""
            SELECT count(*) FROM parking_sessions p JOIN vehicle_rate_versions v ON v.rate_id=p.rate_id AND v.version=p.rate_version
            WHERE p.branch_id=:b AND p.minute_rate=v.minute_rate AND p.full_rate=v.full_rate AND p.block_minutes=v.block_minutes
            """).param("b", t.branchId()).query(Long.class).single();
        assertThat(consistent).isEqualTo(6L);
    }

    @Test
    void concurrentLiquidationsKeepASingleOrder() throws Exception {
        var t = fx.tenant();
        var op = scope(t, fx.user(t, "OPERATOR"));
        fx.capacity(t, "CAR", 5);
        UUID session = (UUID) parking.checkIn(op, fx.vehicle(t, null, "CAR"), null, UUID.randomUUID(), null).get("id");
        db.sql("UPDATE parking_sessions SET entry_at=now()-interval '13 hours'+interval '30 seconds' WHERE id=:id").param("id", session).update();
        int ok = race(6, () -> parking.quote(op, session));
        assertThat(ok).isEqualTo(6);
        assertThat(db.sql("SELECT count(*) FROM payment_orders WHERE session_id=:s").param("s", session).query(Long.class).single()).isEqualTo(1L);
        assertThat(db.sql("SELECT amount FROM payment_orders WHERE session_id=:s").param("s", session).query(BigDecimal.class).single()).isEqualByComparingTo("41000");
    }

    @Test
    void twoCustomersCannotBookTheLastSpace() throws Exception {
        var t = fx.tenant();
        var op = scope(t, fx.user(t, "OPERATOR"));
        UUID space = fx.space(t, "R1", "CAR");
        Instant start = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS), end = start.plus(2, ChronoUnit.HOURS);
        List<UUID[]> pairs = new ArrayList<>();
        for (int i = 0; i < 6; i++) { UUID c = fx.customer(t); pairs.add(new UUID[]{c, fx.vehicle(t, c, "CAR")}); }
        var idx = new AtomicInteger();
        int ok = race(6, () -> { var p = pairs.get(idx.getAndIncrement()); return reservations.create(op, p[0], p[1], space, start, end, UUID.randomUUID()); });
        assertThat(ok).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM reservations WHERE space_id=:s AND status='CONFIRMED'").param("s", space).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void exclusionConstraintBlocksOverlapEvenWithoutApplicationChecks() {
        var t = fx.tenant();
        UUID space = fx.space(t, "X1", "CAR"), c = fx.customer(t), v = fx.vehicle(t, c, "CAR");
        String sql = "INSERT INTO reservations(id,organization_id,branch_id,customer_id,vehicle_id,space_id,starts_at,ends_at,idempotency_key) VALUES(gen_random_uuid(),:o,:b,:c,:v,:s,now()+interval '1 hour',now()+interval '3 hours',gen_random_uuid())";
        db.sql(sql).param("o", t.organizationId()).param("b", t.branchId()).param("c", c).param("v", v).param("s", space).update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql(sql).param("o", t.organizationId()).param("b", t.branchId()).param("c", c).param("v", v).param("s", space).update())
            .hasMessageContaining("exclu");
    }
}
