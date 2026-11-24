package com.smartparking.flows;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.support.Fixtures;
import com.smartparking.support.IntegrationTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Vehicle categories, per-minute billing in 12 h blocks, ticket lookup, discounts and monthly plans, on real PostgreSQL. */
class VehicleBillingIT extends IntegrationTest {
    private static BigDecimal money(JsonNode n) { return new BigDecimal(n.asString()); }
    private static Map<String, Object> rate(String minute, String full, int block, String monthly, int grace, int capacity, boolean active) {
        return Map.of("minuteRate", minute, "fullRate", full, "blockMinutes", block, "monthlyFee", monthly, "graceMinutes", grace, "capacity", capacity, "active", active);
    }
    private JsonNode category(JsonNode list, String type) {
        for (var n : list) if (n.get("vehicle_type").asString().equals(type)) return n;
        throw new AssertionError("Sin categoría " + type);
    }
    private String plateOf(UUID vehicle) { return db.sql("SELECT plate FROM vehicles WHERE id=:id").param("id", vehicle).query(String.class).single(); }
    private void enteredAgo(UUID session, String interval) {
        // +30 s keeps the started-minute count stable while the test runs.
        db.sql("UPDATE parking_sessions SET entry_at=now()-CAST(:i AS interval)+interval '30 seconds' WHERE id=:id").param("i", interval).param("id", session).update();
    }
    private UUID checkIn(Client c, UUID vehicle) throws Exception {
        return UUID.fromString(c.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", vehicle, "idempotencyKey", UUID.randomUUID())).expect(200).body().get("id").asString());
    }

    @Test
    void branchesStartWithTheTemplateRatesAndTheyReproduceTheSpecificationTable() throws Exception {
        var t = fx.tenant();
        var op = login(fx.user(t, "OPERATOR"));
        var rates = op.get("/api/v1/vehicle-rates").expect(200).body();
        assertThat(rates.size()).isEqualTo(4);
        assertThat(List.of(rates.get(0), rates.get(1), rates.get(2), rates.get(3)).stream().map(r -> r.get("vehicle_type").asString()))
            .containsExactly("MOTORCYCLE", "CAR", "BICYCLE", "CARGO");
        var car = category(rates, "CAR");
        assertThat(car.get("label").asString()).isEqualTo("Carro");
        assertThat(car.get("includes").asString()).contains("camionetas", "camperos");
        assertThat(money(car.get("minute_rate"))).isEqualByComparingTo("150");
        assertThat(money(car.get("full_rate"))).isEqualByComparingTo("32000");
        assertThat(car.get("block_minutes").asInt()).isEqualTo(720);
        assertThat(money(car.get("monthly_fee"))).isEqualByComparingTo("180000");
        assertThat(car.get("minutes_to_full_rate").asInt()).isEqualTo(214);
        assertThat(car.get("capacity").asInt()).isZero();
        assertThat(money(category(rates, "MOTORCYCLE").get("full_rate"))).isEqualByComparingTo("15000");
        assertThat(money(category(rates, "BICYCLE").get("monthly_fee"))).isEqualByComparingTo("40000");
        assertThat(money(category(rates, "CARGO").get("minute_rate"))).isEqualByComparingTo("250");

        Instant entry = Instant.parse("2026-05-04T22:10:00Z");
        Map<Long, String> table = Map.of(10L, "1500", 213L, "31950", 214L, "32000", 720L, "32000", 721L, "32150", 934L, "64000", 1500L, "73000", 2220L, "105000", 10080L, "448000");
        for (var row : table.entrySet()) {
            var q = op.post("/api/v1/pricing/quote", Map.of("vehicleType", "CAR", "entryAt", entry.toString(), "exitAt", entry.plus(Duration.ofMinutes(row.getKey())).toString())).expect(200).body();
            assertThat(money(q.get("totalAmount"))).as(row.getKey() + " min").isEqualByComparingTo(row.getValue());
        }
        op.post("/api/v1/pricing/quote", Map.of("vehicleType", "ELECTRIC", "entryAt", entry.toString(), "exitAt", entry.plusSeconds(60).toString())).expect(422);
    }

    @Test
    void onlyAdministratorsChangeRatesAndEveryChangeIsVersionedAndValidated() throws Exception {
        var t = fx.tenant();
        var admin = login(fx.user(t, "ADMIN"));
        var sup = login(fx.user(t, "SUPERVISOR"));
        var op = login(fx.user(t, "OPERATOR"));
        op.put("/api/v1/vehicle-rates/CAR", rate("1", "1", 720, "1", 0, 1, true)).expect(403);
        sup.put("/api/v1/vehicle-rates/CAR", rate("1", "1", 720, "1", 0, 1, true)).expect(403);
        admin.put("/api/v1/vehicle-rates/CAR", rate("150", "100", 720, "180000", 0, 10, true)).expect(422); // full < minute
        admin.put("/api/v1/vehicle-rates/CAR", rate("150", "32000", 60, "180000", 60, 10, true)).expect(422); // courtesy ≥ block
        admin.put("/api/v1/vehicle-rates/CAR", rate("150.001", "32000", 720, "180000", 0, 10, true)).expect(422);
        admin.put("/api/v1/vehicle-rates/ELECTRIC", rate("150", "32000", 720, "180000", 0, 10, true)).expect(422);

        var updated = admin.put("/api/v1/vehicle-rates/CAR", rate("170", "35000", 720, "190000", 5, 40, true)).expect(200).body();
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(money(updated.get("minute_rate"))).isEqualByComparingTo("170");
        var history = sup.get("/api/v1/vehicle-rates/CAR/history").expect(200).body();
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(history.get(0).get("changed_by_name").asString()).isEqualTo("ADMIN de prueba");
        assertThat(money(history.get(1).get("minute_rate"))).isEqualByComparingTo("150");
        op.get("/api/v1/vehicle-rates/CAR/history").expect(403);
        // History rows cannot be rewritten, even directly in the database.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("UPDATE vehicle_rate_versions SET minute_rate=1 WHERE branch_id=:b").param("b", t.branchId()).update())
            .hasMessageContaining("immutable");
        assertThat(db.sql("SELECT count(*) FROM audit_logs WHERE organization_id=:o AND action='VEHICLE_RATE_UPDATED'").param("o", t.organizationId()).query(Long.class).single()).isEqualTo(1L);

        // Another organization's administrator cannot touch this branch.
        var other = fx.tenant();
        var outsider = login(fx.user(other, "ADMIN")).scope(t.organizationId(), t.branchId());
        outsider.put("/api/v1/vehicle-rates/CAR", rate("1", "1", 720, "1", 0, 1, true)).expect(403);
        outsider.get("/api/v1/vehicle-rates").expect(403);
    }

    @Test
    void stayKeepsItsEntryRateAndIsFoundByPlateCodeOrQrWithTheFullBreakdown() throws Exception {
        var t = fx.tenant();
        var admin = login(fx.user(t, "ADMIN"));
        var op = login(fx.user(t, "OPERATOR"));
        fx.capacity(t, "CAR", 5);
        UUID vehicle = fx.vehicle(t, null, "CAR");
        var entered = op.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", vehicle, "idempotencyKey", UUID.randomUUID())).expect(200).body();
        UUID session = UUID.fromString(entered.get("id").asString());
        String code = entered.get("ticket_code").asString(), qr = entered.get("qr_payload").asString();
        assertThat(code).matches("\\d{6}");
        assertThat(qr).isEqualTo("SPK1:" + session);
        assertThat(entered.get("space_id").isNull()).isTrue();
        enteredAgo(session, "13 hours");

        // RN-012: changing the rate after entry does not alter this stay.
        admin.put("/api/v1/vehicle-rates/CAR", rate("300", "60000", 720, "180000", 0, 5, true)).expect(200);
        String plate = plateOf(vehicle);
        var byPlate = op.get("/api/v1/parking-sessions/lookup?q=" + plate.substring(0, 3).toLowerCase() + "-" + plate.substring(3)).expect(200).body();
        assertThat(byPlate.get("sessionId").asString()).isEqualTo(session.toString());
        assertThat(byPlate.get("plate").asString()).isEqualTo(plate);
        assertThat(byPlate.get("vehicleType").asString()).isEqualTo("CAR");
        assertThat(byPlate.get("vehicleTypeLabel").asString()).isEqualTo("Carro");
        assertThat(byPlate.get("ticketStatus").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(byPlate.get("subscription").get("status").asString()).isEqualTo("NONE");
        assertThat(byPlate.get("entryAt").isNull()).isFalse();
        var c = byPlate.get("calculation");
        assertThat(money(c.get("minuteRate"))).isEqualByComparingTo("150");
        assertThat(money(c.get("fullRate"))).isEqualByComparingTo("32000");
        assertThat(c.get("billedMinutes").asLong()).isEqualTo(780);
        assertThat(c.get("staySeconds").asLong()).isBetween(46_770L, 46_800L);
        assertThat(c.get("completedBlocks").asLong()).isEqualTo(1);
        assertThat(c.get("fullRatesReached").asLong()).isEqualTo(1);
        assertThat(money(c.get("previousBlocksAmount"))).isEqualByComparingTo("32000");
        assertThat(c.get("currentBlockNumber").asLong()).isEqualTo(2);
        assertThat(c.get("currentBlockMinutes").asLong()).isEqualTo(60);
        assertThat(money(c.get("currentBlockAmount"))).isEqualByComparingTo("9000");
        assertThat(money(c.get("authorizedDiscounts"))).isEqualByComparingTo("0");
        assertThat(money(c.get("previousPayments"))).isEqualByComparingTo("0");
        assertThat(money(c.get("balanceDue"))).isEqualByComparingTo("41000");
        assertThat(money(c.get("totalAmount"))).isEqualByComparingTo("41000");
        assertThat(c.get("blocks").size()).isEqualTo(2);

        assertThat(op.get("/api/v1/parking-sessions/lookup?q=" + code).expect(200).body().get("sessionId").asString()).isEqualTo(session.toString());
        assertThat(op.get("/api/v1/parking-sessions/lookup?q=" + qr).expect(200).body().get("sessionId").asString()).isEqualTo(session.toString());
        op.get("/api/v1/parking-sessions/lookup?q=SPK1:not-a-uuid").expect(422);
        op.get("/api/v1/parking-sessions/lookup?q=ZZZ999").expect(404);
        var ticket = op.get("/api/v1/parking-sessions/" + session + "/ticket").expect(200).body();
        assertThat(ticket.get("ticketCode").asString()).isEqualTo(code);
        assertThat(ticket.get("notice").asString()).contains("No es una factura electrónica DIAN");
        // Another branch's staff cannot find it, not even with the QR.
        var other = fx.tenant();
        login(fx.user(other, "OPERATOR")).get("/api/v1/parking-sessions/lookup?q=" + qr).expect(404);
        // The simulator, unlike the stay, uses the current rate: 12 h full (60 000) + 60 min × 300.
        var sim = op.post("/api/v1/pricing/quote", Map.of("vehicleType", "CAR", "entryAt", "2026-05-04T08:00:00Z", "exitAt", "2026-05-04T21:00:00Z")).expect(200).body();
        assertThat(money(sim.get("totalAmount"))).isEqualByComparingTo("78000");
        var quoted = op.post("/api/v1/parking-sessions/" + session + "/quote", null).expect(200).body();
        assertThat(money(quoted.get("order").get("amount"))).isEqualByComparingTo("41000");
    }

    @Test
    void discountsNeedSupervisionAndPaymentsReduceTheBalanceUntilExit() throws Exception {
        var t = fx.tenant();
        var opActor = fx.user(t, "OPERATOR");
        var op = login(opActor);
        var sup = login(fx.user(t, "SUPERVISOR"));
        fx.capacity(t, "CAR", 5);
        UUID session = checkIn(op, fx.vehicle(t, null, "CAR"));
        enteredAgo(session, "3 hours");
        UUID key = UUID.randomUUID();
        Map<String, Object> discount = Map.of("amount", "5000", "reason", "Cliente frecuente", "idempotencyKey", key);
        op.post("/api/v1/parking-sessions/" + session + "/discounts", discount).expect(403); // RN-011
        var discounted = sup.post("/api/v1/parking-sessions/" + session + "/discounts", discount).expect(200).body();
        assertThat(money(discounted.get("calculation").get("grossAmount"))).isEqualByComparingTo("27000");
        assertThat(money(discounted.get("calculation").get("authorizedDiscounts"))).isEqualByComparingTo("5000");
        assertThat(money(discounted.get("calculation").get("totalAmount"))).isEqualByComparingTo("22000");
        assertThat(discounted.get("discounts").get(0).get("reason").asString()).isEqualTo("Cliente frecuente");
        sup.post("/api/v1/parking-sessions/" + session + "/discounts", discount).expect(200); // same key: no second discount
        assertThat(db.sql("SELECT count(*) FROM session_discounts WHERE session_id=:s").param("s", session).query(Long.class).single()).isEqualTo(1L);
        sup.post("/api/v1/parking-sessions/" + session + "/discounts", Map.of("amount", "30000", "reason", "Excesivo", "idempotencyKey", UUID.randomUUID())).expect(409);
        sup.post("/api/v1/parking-sessions/" + session + "/discounts", Map.of("amount", "100", "reason", " ", "idempotencyKey", UUID.randomUUID())).expect(422);

        var order = op.post("/api/v1/parking-sessions/" + session + "/quote", null).expect(200).body().get("order");
        assertThat(money(order.get("amount"))).isEqualByComparingTo("22000");
        UUID cash = UUID.fromString(op.post("/api/v1/cash-sessions/open", Map.of("openingBalance", "0")).expect(200).body().get("id").asString());
        op.post("/api/v1/payments", Map.of("orderId", order.get("id").asString(), "cashSessionId", cash, "method", "CASH", "amount", "10000", "idempotencyKey", UUID.randomUUID())).expect(200);
        var partial = op.get("/api/v1/parking-sessions/" + session + "/liquidation").expect(200).body();
        assertThat(money(partial.get("calculation").get("previousPayments"))).isEqualByComparingTo("10000");
        assertThat(money(partial.get("calculation").get("balanceDue"))).isEqualByComparingTo("12000");
        assertThat(partial.get("ticketStatus").asString()).isEqualTo("PENDING_PAYMENT");
        op.post("/api/v1/parking-sessions/" + session + "/check-out", null).expect(409);
        op.post("/api/v1/payments", Map.of("orderId", order.get("id").asString(), "cashSessionId", cash, "method", "CASH", "amount", "12001", "idempotencyKey", UUID.randomUUID())).expect(409);
        op.post("/api/v1/payments", Map.of("orderId", order.get("id").asString(), "cashSessionId", cash, "method", "CASH", "amount", "12000", "idempotencyKey", UUID.randomUUID())).expect(200);
        var paid = op.get("/api/v1/parking-sessions/" + session + "/liquidation").expect(200).body();
        assertThat(paid.get("ticketStatus").asString()).isEqualTo("PAID");
        assertThat(money(paid.get("calculation").get("balanceDue"))).isEqualByComparingTo("0");
        var closed = op.post("/api/v1/parking-sessions/" + session + "/check-out", null).expect(200).body();
        assertThat(money(closed.get("final_amount"))).isEqualByComparingTo("22000");
        // A closed stay keeps showing the liquidation it was paid for.
        var after = op.get("/api/v1/parking-sessions/" + session + "/liquidation").expect(200).body();
        assertThat(after.get("ticketStatus").asString()).isEqualTo("CLOSED");
        assertThat(money(after.get("calculation").get("totalAmount"))).isEqualByComparingTo("22000");
        assertThat(db.sql("SELECT count(*) FROM audit_logs WHERE organization_id=:o AND action='DISCOUNT_GRANTED'").param("o", t.organizationId()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void paidStayIsRepricedWhenLiquidatedAgainAfterMoreTime() throws Exception {
        var t = fx.tenant();
        var op = login(fx.user(t, "OPERATOR"));
        fx.capacity(t, "CAR", 5);
        UUID session = checkIn(op, fx.vehicle(t, null, "CAR"));
        enteredAgo(session, "30 minutes");
        var order = op.post("/api/v1/parking-sessions/" + session + "/quote", null).expect(200).body().get("order");
        UUID cash = UUID.fromString(op.post("/api/v1/cash-sessions/open", Map.of("openingBalance", "0")).expect(200).body().get("id").asString());
        op.post("/api/v1/payments", Map.of("orderId", order.get("id").asString(), "cashSessionId", cash, "method", "CASH", "amount", "4500", "idempotencyKey", UUID.randomUUID())).expect(200);
        assertThat(op.get("/api/v1/parking-sessions/" + session + "/liquidation").expect(200).body().get("ticketStatus").asString()).isEqualTo("PAID");
        enteredAgo(session, "4 hours"); // the vehicle stayed much longer after paying
        var again = op.post("/api/v1/parking-sessions/" + session + "/quote", null).expect(200).body();
        assertThat(again.get("ticketStatus").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(money(again.get("order").get("amount"))).isEqualByComparingTo("32000");
        assertThat(money(again.get("calculation").get("balanceDue"))).isEqualByComparingTo("27500");
        op.post("/api/v1/parking-sessions/" + session + "/check-out", null).expect(409);
    }

    @Test
    void capacityIsPerCategoryAndBicyclesUseTheirOwnIdentifier() throws Exception {
        var t = fx.tenant();
        var admin = login(fx.user(t, "ADMIN"));
        var op = login(fx.user(t, "OPERATOR"));
        fx.capacity(t, "CAR", 2);
        UUID reservedCar = fx.vehicle(t, fx.customer(t), "CAR");
        db.sql("INSERT INTO reservations(id,organization_id,branch_id,customer_id,vehicle_id,space_id,starts_at,ends_at,idempotency_key) SELECT gen_random_uuid(),:o,:b,customer_id,id,:s,now()-interval '5 minutes',now()+interval '1 hour',gen_random_uuid() FROM vehicles WHERE id=:v")
            .param("o", t.organizationId()).param("b", t.branchId()).param("s", fx.space(t, "R-1", "CAR")).param("v", reservedCar).update();
        checkIn(op, fx.vehicle(t, null, "CAR"));
        // 1 open stay + 1 reservation in force = capacity 2: a second walk-in is refused.
        var full = op.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", fx.vehicle(t, null, "CAR"), "idempotencyKey", UUID.randomUUID())).expect(409).text();
        assertThat(full).contains("Sin cupo disponible para Carro (capacidad 2)");
        assertThat(op.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", fx.vehicle(t, null, "MOTORCYCLE"), "idempotencyKey", UUID.randomUUID())).expect(409).text())
            .contains("Configure la capacidad de Motocicleta");

        // Bicycles have no plate: a registry or serial number identifies them.
        fx.capacity(t, "BICYCLE", 2);
        UUID bike = UUID.fromString(op.post("/api/v1/catalogs/vehicles", Map.of("plate", "bici-0042", "country_code", "CO", "type", "BICYCLE")).expect(200).body().get("id").asString());
        assertThat(plateOf(bike)).isEqualTo("BICI0042");
        op.post("/api/v1/catalogs/vehicles", Map.of("plate", "BICI0042X", "country_code", "CO", "type", "CAR")).expect(422);
        UUID bikeStay = checkIn(op, bike);
        var found = op.get("/api/v1/parking-sessions/lookup?q=BICI0042").expect(200).body();
        assertThat(found.get("sessionId").asString()).isEqualTo(bikeStay.toString());
        assertThat(found.get("vehicleTypeLabel").asString()).isEqualTo("Bicicleta");
        assertThat(money(found.get("calculation").get("minuteRate"))).isEqualByComparingTo("30");

        // Only the four categories exist.
        op.post("/api/v1/catalogs/vehicles", Map.of("plate", "EVV123", "country_code", "CO", "type", "ELECTRIC")).expect(422);
        admin.post("/api/v1/catalogs/spaces", Map.of("code", "X-1", "type", "ACCESSIBLE", "status", "AVAILABLE")).expect(422);
        var accessible = admin.post("/api/v1/catalogs/spaces", Map.of("code", "A-1", "type", "CAR", "feature", "ACCESSIBLE", "status", "AVAILABLE")).expect(200).body();
        assertThat(db.sql("SELECT feature FROM parking_spaces WHERE id=:id").param("id", UUID.fromString(accessible.get("id").asString())).query(String.class).single()).isEqualTo("ACCESSIBLE");

        // An inactive category admits no vehicles.
        admin.put("/api/v1/vehicle-rates/CARGO", rate("250", "50000", 720, "350000", 0, 5, false)).expect(200);
        assertThat(op.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", fx.vehicle(t, null, "CARGO"), "idempotencyKey", UUID.randomUUID())).expect(409).text())
            .contains("no está habilitada");
        var availability = op.get("/api/v1/spaces/availability").expect(200).body().get("types");
        for (var a : availability) if (a.get("type").asString().equals("CAR")) assertThat(a.get("available").asInt()).isZero();
    }

    // ---------------- monthly plans per category

    private UUID activeContract(Fixtures.Tenant t, UUID plan, UUID customer, UUID vehicle, Instant start, Instant end) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO subscriptions(id,organization_id,branch_id,customer_id,vehicle_id,plan_id,starts_at,ends_at,status,price) VALUES(:id,:o,:b,:c,:v,:p,:s,:e,'ACTIVE',1)")
            .param("id", id).param("o", t.organizationId()).param("b", t.branchId()).param("c", customer).param("v", vehicle).param("p", plan)
            .param("s", Timestamp.from(start)).param("e", Timestamp.from(end)).update();
        return id;
    }

    @Test
    void plansBelongToOneCategoryAndDefaultToTheCategoryMonthlyFee() throws Exception {
        var t = fx.tenant();
        var sup = login(fx.user(t, "SUPERVISOR"));
        UUID customer = fx.customer(t), moto = fx.vehicle(t, customer, "MOTORCYCLE"), car = fx.vehicle(t, customer, "CAR");
        UUID motoPlan = fx.plan(t, "MOTORCYCLE", null, 30);
        var wrong = sup.post("/api/v1/subscriptions", Map.of("customerId", customer, "vehicleId", car, "planId", motoPlan, "startsAt", Instant.now().toString())).expect(422).text();
        assertThat(wrong).contains("El plan es para Motocicleta y el vehículo es Carro");
        var contract = sup.post("/api/v1/subscriptions", Map.of("customerId", customer, "vehicleId", moto, "planId", motoPlan, "startsAt", Instant.now().toString())).expect(200).body();
        assertThat(money(contract.get("price"))).isEqualByComparingTo("90000"); // motorcycle monthly fee of the branch
        // Explicit end date, at most one year.
        UUID carPlan = fx.plan(t, "CAR", "150000", 30);
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var custom = sup.post("/api/v1/subscriptions", Map.of("customerId", customer, "vehicleId", car, "planId", carPlan, "startsAt", start.toString(), "endsAt", start.plus(Duration.ofDays(15)).toString())).expect(200).body();
        assertThat(db.sql("SELECT ends_at FROM subscriptions WHERE id=:id").param("id", UUID.fromString(custom.get("id").asString())).query(Timestamp.class).single().toInstant())
            .isEqualTo(start.plus(Duration.ofDays(15)));

        // Admin creates a plan with allowed days, hours and grace through the catalog.
        var admin = login(fx.user(t, "ADMIN"));
        admin.post("/api/v1/catalogs/subscription-plans", Map.of("name", "Nocturno", "vehicle_type", "CAR", "duration_days", "30", "allowed_days", "12345", "allowed_start", "20:00")).expect(422);
        admin.post("/api/v1/catalogs/subscription-plans", Map.of("name", "Nocturno", "vehicle_type", "CAR", "duration_days", "30", "allowed_days", "189", "allowed_start", "20:00", "allowed_end", "06:00")).expect(422);
        UUID night = UUID.fromString(admin.post("/api/v1/catalogs/subscription-plans", Map.of("name", "Nocturno", "vehicle_type", "CAR", "duration_days", "30",
            "allowed_days", "1,2,3,4,5", "allowed_start", "20:00", "allowed_end", "06:00", "grace_days", "2")).expect(200).body().get("id").asString());
        var row = db.sql("SELECT allowed_days,grace_days,price FROM subscription_plans WHERE id=:id").param("id", night).query().singleRow();
        assertThat(row.get("allowed_days")).isEqualTo("12345");
        assertThat(row.get("grace_days")).isEqualTo(2);
        assertThat(row.get("price")).isNull();
    }

    @Test
    void monthlyCustomerPaysOnlyOutsideTheAllowedHours() throws Exception {
        var t = fx.tenant();
        var op = login(fx.user(t, "OPERATOR"));
        fx.capacity(t, "CAR", 5);
        UUID customer = fx.customer(t), car = fx.vehicle(t, customer, "CAR");
        ZoneId zone = ZoneId.of("America/Bogota");
        Instant entry = db.sql("SELECT date_trunc('minute', now()) - interval '3 hours'").query(Timestamp.class).single().toInstant();
        // Daily window from entry+1h to entry+4h (local time): the first hour of the stay is outside it.
        UUID plan = fx.plan(t, "CAR", "150000", 30);
        db.sql("UPDATE subscription_plans SET allowed_start=:s, allowed_end=:e WHERE id=:id")
            .param("s", java.sql.Time.valueOf(LocalTime.ofInstant(entry.plus(Duration.ofHours(1)), zone))).param("e", java.sql.Time.valueOf(LocalTime.ofInstant(entry.plus(Duration.ofHours(4)), zone)))
            .param("id", plan).update();
        activeContract(t, plan, customer, car, entry.minus(Duration.ofDays(2)), entry.plus(Duration.ofDays(28)));
        var entered = op.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", car, "idempotencyKey", UUID.randomUUID())).expect(200).body();
        assertThat(entered.get("monthly_customer").asBoolean()).isTrue();
        UUID session = UUID.fromString(entered.get("id").asString());
        db.sql("UPDATE parking_sessions SET entry_at=:e WHERE id=:id").param("e", Timestamp.from(entry)).param("id", session).update();
        var l = op.get("/api/v1/parking-sessions/" + session + "/liquidation").expect(200).body();
        assertThat(l.get("monthlyCustomer").asBoolean()).isTrue();
        assertThat(l.get("subscription").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(money(l.get("calculation").get("totalAmount"))).isEqualByComparingTo("9000"); // only the first 60 minutes
        assertThat(l.get("calculation").get("coveredMinutes").asLong()).isEqualTo(l.get("calculation").get("billedMinutes").asLong() - 60);
    }

    @Test
    void authorizedBranchesAndGraceDaysDecideCoverage() throws Exception {
        var t = fx.tenant();
        UUID second = fx.branch(t, "B2"), third = fx.branch(t, "B3");
        var adminActor = fx.user(t, "ADMIN");
        var admin = login(adminActor);
        var sup = login(fx.user(t, "SUPERVISOR"));
        fx.capacity(second, "MOTORCYCLE", 5);
        fx.capacity(third, "MOTORCYCLE", 5);
        UUID customer = fx.customer(t), moto = fx.vehicle(t, customer, "MOTORCYCLE"), moto2 = fx.vehicle(t, customer, "MOTORCYCLE");
        UUID plan = fx.plan(t, "MOTORCYCLE", null, 30);
        sup.put("/api/v1/subscription-plans/" + plan + "/branches", Map.of("branchIds", List.of(second))).expect(403);
        login(fx.user(fx.tenant(), "ADMIN")).put("/api/v1/subscription-plans/" + plan + "/branches", Map.of("branchIds", List.of(second))).expect(404); // not their plan
        var foreign = fx.tenant();
        admin.put("/api/v1/subscription-plans/" + plan + "/branches", Map.of("branchIds", List.of(foreign.branchId()))).expect(403);
        var branches = admin.put("/api/v1/subscription-plans/" + plan + "/branches", Map.of("branchIds", List.of(second))).expect(200).body();
        assertThat(branches.size()).isEqualTo(2);
        Instant now = Instant.now();
        activeContract(t, plan, customer, moto, now.minus(Duration.ofDays(5)), now.plus(Duration.ofDays(25)));

        var inSecond = admin.scope(t.organizationId(), second);
        UUID covered = checkIn(inSecond, moto);
        enteredAgo(covered, "2 hours");
        assertThat(money(inSecond.get("/api/v1/parking-sessions/" + covered + "/liquidation").expect(200).body().get("calculation").get("totalAmount"))).isEqualByComparingTo("0");
        var inThird = admin.scope(t.organizationId(), third);
        UUID charged = checkIn(inThird, moto2);
        enteredAgo(charged, "2 hours");
        assertThat(money(inThird.get("/api/v1/parking-sessions/" + charged + "/liquidation").expect(200).body().get("calculation").get("totalAmount"))).isEqualByComparingTo("9600"); // 120 × 80

        // Expired yesterday: covered only while the plan grants grace days.
        UUID expiredMoto = fx.vehicle(t, customer, "MOTORCYCLE");
        UUID contract = activeContract(t, plan, customer, expiredMoto, now.minus(Duration.ofDays(31)), now.minus(Duration.ofDays(1)));
        db.sql("UPDATE subscriptions SET status='EXPIRED' WHERE id=:id").param("id", contract).update();
        var op = login(fx.user(t, "OPERATOR"));
        fx.capacity(t, "MOTORCYCLE", 5);
        UUID stay = checkIn(op, expiredMoto);
        enteredAgo(stay, "1 hour");
        var noGrace = op.get("/api/v1/parking-sessions/" + stay + "/liquidation").expect(200).body();
        assertThat(noGrace.get("subscription").get("status").asString()).isEqualTo("EXPIRED");
        assertThat(money(noGrace.get("calculation").get("totalAmount"))).isEqualByComparingTo("4800");
        db.sql("UPDATE subscription_plans SET grace_days=3 WHERE id=:id").param("id", plan).update();
        var withGrace = op.get("/api/v1/parking-sessions/" + stay + "/liquidation").expect(200).body();
        assertThat(withGrace.get("subscription").get("status").asString()).isEqualTo("GRACE");
        assertThat(money(withGrace.get("calculation").get("totalAmount"))).isEqualByComparingTo("0");
    }
}
