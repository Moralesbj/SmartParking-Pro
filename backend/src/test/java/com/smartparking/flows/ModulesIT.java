package com.smartparking.flows;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.modules.notifications.application.NotificationService;
import com.smartparking.modules.notifications.application.OperationalJobs;
import com.smartparking.modules.subscriptions.application.SubscriptionService;
import com.smartparking.support.Fixtures;
import com.smartparking.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** M06, M09, M10, M13, M16 against real PostgreSQL. */
class ModulesIT extends IntegrationTest {
    @Autowired SubscriptionService subscriptions;
    @Autowired NotificationService notifications;
    @Autowired OperationalJobs.DeviceMonitor deviceMonitor;

    private UUID id(Response r) throws Exception { return UUID.fromString(r.body().get("id").asString()); }

    // ---------------- M10 subscriptions + M16 notifications
    private UUID payOrderFor(Fixtures.Tenant t, Client cashier, UUID subscription) throws Exception {
        var order = db.sql("SELECT id,amount FROM payment_orders WHERE subscription_id=:s").param("s", subscription).query().singleRow();
        var cash = db.sql("SELECT id FROM cash_sessions WHERE organization_id=:o AND status='OPEN' LIMIT 1").param("o", t.organizationId()).query(UUID.class).optional();
        UUID cashId = cash.isPresent() ? cash.get() : id(cashier.post("/api/v1/cash-sessions/open", Map.of("openingBalance", "0")).expect(200));
        cashier.post("/api/v1/payments", Map.of("orderId", order.get("id"), "cashSessionId", cashId, "method", "TRANSFER", "amount", order.get("amount").toString(),
            "reference", "TRX-" + subscription.toString().substring(0, 6), "idempotencyKey", UUID.randomUUID())).expect(200);
        return (UUID) order.get("id");
    }

    @Test
    void subscriptionLifecycleRenewalAndExpiryNotice() throws Exception {
        var t = fx.tenant();
        var customerActor = fx.user(t, "CUSTOMER");
        UUID customer = fx.customerOf(customerActor), vehicle = fx.vehicle(t, customer, "CAR");
        var sup = login(fx.user(t, "SUPERVISOR"));
        var opActor = fx.user(t, "OPERATOR");
        var op = login(opActor);
        UUID plan = fx.plan(t, "CAR", "150000", 30);
        fx.capacity(t, "CAR", 5);

        op.post("/api/v1/subscriptions", Map.of("customerId", customer, "vehicleId", vehicle, "planId", plan, "startsAt", Instant.now().toString())).expect(403);
        Instant start = Instant.now().minus(28, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UUID sub = id(sup.post("/api/v1/subscriptions", Map.of("customerId", customer, "vehicleId", vehicle, "planId", plan, "startsAt", Instant.now().toString())).expect(200));
        assertThat(db.sql("SELECT status FROM subscriptions WHERE id=:id").param("id", sub).query(String.class).single()).isEqualTo("PENDING");
        // Overlapping second contract for the same vehicle is rejected by the exclusion constraint.
        sup.post("/api/v1/subscriptions", Map.of("customerId", customer, "vehicleId", vehicle, "planId", plan, "startsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString())).expect(409);

        // Unpaid contract grants nothing.
        UUID space = fx.space(t, "M1", "CAR");
        UUID session = id(op.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", vehicle, "spaceId", space, "idempotencyKey", UUID.randomUUID())).expect(200));
        db.sql("UPDATE parking_sessions SET entry_at=now()-interval '90 minutes'+interval '30 seconds' WHERE id=:id").param("id", session).update();
        var unpaid = op.post("/api/v1/parking-sessions/" + session + "/quote", null).expect(200).body();
        assertThat(new BigDecimal(unpaid.get("order").get("amount").asString())).isEqualByComparingTo("13500.00"); // 90 min × 150
        assertThat(unpaid.get("subscription").get("status").asString()).isEqualTo("PENDING");

        // Paying activates it; the contract is back-dated so it covers the stay and is about to expire.
        payOrderFor(t, op, sub);
        db.sql("UPDATE subscriptions SET starts_at=:s,ends_at=:e WHERE id=:id").param("s", java.sql.Timestamp.from(start)).param("e", java.sql.Timestamp.from(start.plus(30, ChronoUnit.DAYS))).param("id", sub).update();
        assertThat(db.sql("SELECT status FROM subscriptions WHERE id=:id").param("id", sub).query(String.class).single()).isEqualTo("ACTIVE");
        var covered = op.post("/api/v1/parking-sessions/" + session + "/quote", null).expect(200).body();
        assertThat(new BigDecimal(covered.get("order").get("amount").asString())).isEqualByComparingTo("0.00");
        assertThat(covered.get("calculation").get("coveredMinutes").asLong()).isEqualTo(90);
        assertThat(covered.get("subscription").get("status").asString()).isEqualTo("ACTIVE");

        // Expiry notice: exactly one event and one customer notification, however often the jobs run.
        assertThat(subscriptions.processLifecycle()).isEqualTo(1);
        assertThat(subscriptions.processLifecycle()).isZero();
        notifications.deliverPending();
        notifications.deliverPending();
        assertThat(db.sql("SELECT count(*) FROM notifications WHERE recipient_user_id=:u AND template_code='SUBSCRIPTION_EXPIRING'").param("u", customerActor.userId()).query(Long.class).single()).isEqualTo(1L);
        var portal = login(customerActor);
        assertThat(portal.get("/api/v1/notifications").expect(200).text()).contains("Mensualidad por vencer");

        // Renewal starts where the current period ends; only one renewal per contract.
        sup.post("/api/v1/subscriptions/" + sub + "/renew", null).expect(200);
        assertThat(db.sql("SELECT starts_at=:e FROM subscriptions WHERE renewal_of=:id").param("e", java.sql.Timestamp.from(start.plus(30, ChronoUnit.DAYS))).param("id", sub).query(Boolean.class).single()).isTrue();
        sup.post("/api/v1/subscriptions/" + sub + "/renew", null).expect(409);

        // Suspension removes the benefit immediately: liquidating again charges the stay and reopens the order.
        sup.post("/api/v1/subscriptions/" + sub + "/suspend", null).expect(200);
        var suspended = op.post("/api/v1/parking-sessions/" + session + "/quote", null).expect(200).body();
        assertThat(new BigDecimal(suspended.get("order").get("amount").asString())).isEqualByComparingTo("13500.00");
        assertThat(suspended.get("ticketStatus").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(suspended.get("subscription").get("status").asString()).isEqualTo("SUSPENDED");
        UUID session2 = id(op.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", fx.vehicle(t, customer, "CAR"), "spaceId", fx.space(t, "M2", "CAR"), "idempotencyKey", UUID.randomUUID())).expect(200));
        assertThat(session2).isNotNull();
        sup.post("/api/v1/subscriptions/" + sub + "/reactivate", null).expect(200);
        assertThat(db.sql("SELECT status FROM subscriptions WHERE id=:id").param("id", sub).query(String.class).single()).isEqualTo("ACTIVE");
        // Reactivated within the paid period: the stay is covered again and the order settles at zero.
        var reactivated = op.post("/api/v1/parking-sessions/" + session + "/quote", null).expect(200).body();
        assertThat(new BigDecimal(reactivated.get("order").get("amount").asString())).isEqualByComparingTo("0.00");
        assertThat(reactivated.get("ticketStatus").asString()).isEqualTo("PAID");

        // Expired contract grants nothing.
        db.sql("UPDATE subscriptions SET ends_at=now()-interval '1 minute' WHERE id=:id").param("id", sub).update();
        subscriptions.processLifecycle();
        assertThat(db.sql("SELECT status FROM subscriptions WHERE id=:id").param("id", sub).query(String.class).single()).isEqualTo("EXPIRED");
    }

    @Test
    void notificationPreferencesAreHonouredAndExternalChannelsCannotBeFaked() throws Exception {
        var t = fx.tenant();
        var supActor = fx.user(t, "SUPERVISOR");
        var sup = login(supActor);
        sup.put("/api/v1/notification-preferences", Map.of("eventType", "DeviceOffline", "channel", "SMS", "enabled", true)).expect(409);
        var prefs = sup.get("/api/v1/notification-preferences").expect(200).body();
        assertThat(prefs.toString()).contains("\"providerConfigured\":false");

        UUID device = UUID.randomUUID();
        db.sql("INSERT INTO devices(id,organization_id,branch_id,name,type,status,last_heartbeat_at) VALUES(:id,:o,:b,'Barrera','BARRIER','ONLINE',now()-interval '10 minutes')")
            .param("id", device).param("o", t.organizationId()).param("b", t.branchId()).update();
        var otherSup = fx.user(t, "SUPERVISOR");
        sup.put("/api/v1/notification-preferences", Map.of("eventType", "DeviceOffline", "channel", "IN_APP", "enabled", false)).expect(200);
        assertThat(deviceMonitor.markOffline()).isGreaterThanOrEqualTo(1);
        notifications.deliverPending();
        assertThat(db.sql("SELECT status FROM devices WHERE id=:id").param("id", device).query(String.class).single()).isEqualTo("OFFLINE");
        assertThat(db.sql("SELECT count(*) FROM anomaly_alerts WHERE reference_id=:id AND type='DEVICE_OFFLINE'").param("id", device).query(Long.class).single()).isEqualTo(1L);
        assertThat(db.sql("SELECT count(*) FROM notifications WHERE recipient_user_id=:u AND template_code='DEVICE_OFFLINE'").param("u", supActor.userId()).query(Long.class).single()).isZero();
        assertThat(db.sql("SELECT count(*) FROM notifications WHERE recipient_user_id=:u AND template_code='DEVICE_OFFLINE'").param("u", otherSup.userId()).query(Long.class).single()).isEqualTo(1L);
    }

    // ---------------- M13 shifts
    @Test
    void shiftsCannotOverlapAndFollowTheirWindow() throws Exception {
        var t = fx.tenant();
        var sup = login(fx.user(t, "SUPERVISOR"));
        var opActor = fx.user(t, "OPERATOR");
        var op = login(opActor);
        var other = login(fx.user(t, "OPERATOR"));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Map<String, Object> current = Map.of("operatorId", opActor.userId(), "scheduledStart", now.minus(5, ChronoUnit.MINUTES).toString(), "scheduledEnd", now.plus(8, ChronoUnit.HOURS).toString());
        UUID shift = id(sup.post("/api/v1/operator-shifts", current).expect(200));
        sup.post("/api/v1/operator-shifts", Map.of("operatorId", opActor.userId(), "scheduledStart", now.plus(1, ChronoUnit.HOURS).toString(), "scheduledEnd", now.plus(9, ChronoUnit.HOURS).toString())).expect(409);
        sup.post("/api/v1/operator-shifts", Map.of("operatorId", opActor.userId(), "scheduledStart", now.toString(), "scheduledEnd", now.plus(17, ChronoUnit.HOURS).toString())).expect(422);
        op.post("/api/v1/operator-shifts", current).expect(403);

        UUID future = id(sup.post("/api/v1/operator-shifts", Map.of("operatorId", opActor.userId(), "scheduledStart", now.plus(2, ChronoUnit.DAYS).toString(), "scheduledEnd", now.plus(2, ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS).toString())).expect(200));
        op.post("/api/v1/operator-shifts/" + future + "/start", null).expect(409); // too early
        op.post("/api/v1/operator-shifts/" + future + "/cancel", null).expect(403);
        sup.post("/api/v1/operator-shifts/" + future + "/cancel", null).expect(200);
        // Cancelled slot is free again.
        sup.post("/api/v1/operator-shifts", Map.of("operatorId", opActor.userId(), "scheduledStart", now.plus(2, ChronoUnit.DAYS).toString(), "scheduledEnd", now.plus(2, ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS).toString())).expect(200);

        other.post("/api/v1/operator-shifts/" + shift + "/start", null).expect(403);
        op.post("/api/v1/operator-shifts/" + shift + "/start", null).expect(200);
        op.post("/api/v1/operator-shifts/" + shift + "/start", null).expect(409);
        op.post("/api/v1/operator-shifts/" + shift + "/incidents", Map.of("kind", "HANDOVER", "detail", "Entrega de caja al relevo")).expect(200);
        other.get("/api/v1/operator-shifts/" + shift + "/incidents").expect(403);
        assertThat(op.get("/api/v1/operator-shifts/" + shift + "/incidents").expect(200).text()).contains("HANDOVER");
        op.post("/api/v1/operator-shifts/" + shift + "/end", null).expect(200);
        // Operators list only their own shifts.
        assertThat(other.get("/api/v1/operator-shifts").expect(200).body().size()).isZero();
        assertThat(sup.get("/api/v1/operator-shifts").expect(200).body().size()).isEqualTo(3);
    }

    // ---------------- M06 capacity
    @Test
    void availabilityAndMapReflectSessionsReservationsAndBlocks() throws Exception {
        var t = fx.tenant();
        var op = login(fx.user(t, "OPERATOR"));
        fx.capacity(t, "CAR", 3);
        UUID zone = fx.zone(t);
        UUID a = fx.space(t, "A1", "CAR"), b = fx.space(t, "A2", "CAR"), c = fx.space(t, "A3", "CAR");
        fx.space(t, "B1", "MOTORCYCLE");
        db.sql("UPDATE parking_spaces SET zone_id=:z WHERE id IN (:a,:b)").param("z", zone).param("a", a).param("b", b).update();
        db.sql("UPDATE parking_spaces SET status='MAINTENANCE' WHERE id=:c").param("c", c).update();
        op.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", fx.vehicle(t, null, "CAR"), "spaceId", a, "idempotencyKey", UUID.randomUUID())).expect(200);
        UUID cust = fx.customer(t);
        db.sql("INSERT INTO reservations(id,organization_id,branch_id,customer_id,vehicle_id,space_id,starts_at,ends_at,idempotency_key) VALUES(gen_random_uuid(),:o,:br,:c,:v,:s,now()-interval '5 minutes',now()+interval '1 hour',gen_random_uuid())")
            .param("o", t.organizationId()).param("br", t.branchId()).param("c", cust).param("v", fx.vehicle(t, cust, "CAR")).param("s", b).update();

        var types = op.get("/api/v1/spaces/availability").expect(200).body().get("types");
        assertThat(types.size()).isEqualTo(4); // one entry per vehicle category, from the branch configuration
        tools.jackson.databind.JsonNode car = null;
        for (var type : types) if (type.get("type").asString().equals("CAR")) car = type;
        assertThat(car.get("total").asInt()).isEqualTo(3); // configured capacity
        assertThat(car.get("occupied").asInt()).isEqualTo(1);
        assertThat(car.get("reserved").asInt()).isEqualTo(1);
        assertThat(car.get("unavailable").asInt()).isEqualTo(1);
        assertThat(car.get("spaces").asInt()).isEqualTo(3);
        assertThat(car.get("available").asInt()).isEqualTo(1);

        var map = op.get("/api/v1/spaces/map").expect(200).body();
        assertThat(map.size()).isEqualTo(2);
        assertThat(map.get(0).get("name").asString()).isEqualTo("Zona A");
        assertThat(map.toString()).contains("\"OCCUPIED\"", "\"RESERVED\"", "\"MAINTENANCE\"");
    }
}
