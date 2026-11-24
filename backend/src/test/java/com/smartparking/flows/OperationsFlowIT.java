package com.smartparking.flows;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Flow A: configure branch → operator opens cash → entry → quote → payment → receipt → exit → close cash → report. */
class OperationsFlowIT extends IntegrationTest {

    @Test
    void fullParkingCycleIsConsistentWithCashAndReports() throws Exception {
        var t = fx.tenant();
        var admin = login(fx.user(t, "ADMIN"));
        var operatorActor = fx.user(t, "OPERATOR");
        var operator = login(operatorActor);

        UUID zone = UUID.fromString(admin.post("/api/v1/catalogs/zones", Map.of("name", "Sótano", "level", "-1")).expect(200).body().get("id").asString());
        UUID space = UUID.fromString(admin.post("/api/v1/catalogs/spaces", Map.of("code", "S-01", "zone_id", zone.toString(), "type", "CAR", "status", "AVAILABLE")).expect(200).body().get("id").asString());
        // The branch comes with the template rates (car: 150/min, full rate 32 000 per 12 h); the admin declares capacity.
        var carRate = Map.of("minuteRate", "150", "fullRate", "32000", "blockMinutes", 720, "monthlyFee", "180000", "graceMinutes", 0, "capacity", 10, "active", true);
        admin.put("/api/v1/vehicle-rates/CAR", carRate).expect(200);

        // Operator may not change tariffs (M02 acceptance) but can read them.
        operator.put("/api/v1/vehicle-rates/MOTORCYCLE", carRate).expect(403);
        assertThat(operator.get("/api/v1/vehicle-rates").expect(200).body().size()).isEqualTo(4);

        UUID vehicle = UUID.fromString(operator.post("/api/v1/catalogs/vehicles", Map.of("plate", "abc-123", "country_code", "CO", "type", "CAR")).expect(200).body().get("id").asString());
        assertThat(db.sql("SELECT plate FROM vehicles WHERE id=:id").param("id", vehicle).query(String.class).single()).isEqualTo("ABC123");

        UUID cash = UUID.fromString(operator.post("/api/v1/cash-sessions/open", Map.of("openingBalance", "50000")).expect(200).body().get("id").asString());

        UUID key = UUID.randomUUID();
        var session = operator.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", vehicle, "spaceId", space, "idempotencyKey", key)).expect(200).body();
        UUID sessionId = UUID.fromString(session.get("id").asString());
        // Idempotent retry returns the same session.
        assertThat(operator.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", vehicle, "spaceId", space, "idempotencyKey", key)).expect(200).body().get("id").asString())
            .isEqualTo(sessionId.toString());

        // Exit blocked without payment.
        operator.post("/api/v1/parking-sessions/" + sessionId + "/check-out", null).expect(409);

        db.sql("UPDATE parking_sessions SET entry_at=now()-interval '95 minutes'+interval '30 seconds' WHERE id=:id").param("id", sessionId).update();
        var order = operator.post("/api/v1/parking-sessions/" + sessionId + "/quote", null).expect(200).body().get("order");
        assertThat(new BigDecimal(order.get("amount").asString())).isEqualByComparingTo("14250.00"); // 95 min × 150

        UUID orderId = UUID.fromString(order.get("id").asString());
        UUID payKey = UUID.randomUUID();
        var payment = operator.post("/api/v1/payments", Map.of("orderId", orderId, "cashSessionId", cash, "method", "CASH", "amount", "14250", "idempotencyKey", payKey)).expect(200).body();
        // Double click: same key returns the same payment, no second row.
        assertThat(operator.post("/api/v1/payments", Map.of("orderId", orderId, "cashSessionId", cash, "method", "CASH", "amount", "14250", "idempotencyKey", payKey)).expect(200).body().get("id").asString())
            .isEqualTo(payment.get("id").asString());
        assertThat(db.sql("SELECT count(*) FROM payments WHERE order_id=:o").param("o", orderId).query(Long.class).single()).isEqualTo(1L);

        var receipt = operator.get("/api/v1/payments/" + payment.get("id").asString() + "/receipt").expect(200).body();
        assertThat(receipt.get("kind").asString()).isEqualTo("RECIBO_INTERNO");
        assertThat(receipt.get("notice").asString()).contains("No constituye factura electrónica DIAN");

        var closed = operator.post("/api/v1/parking-sessions/" + sessionId + "/check-out", null).expect(200).body();
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(new BigDecimal(closed.get("final_amount").asString())).isEqualByComparingTo("14250.00");

        var cashClosed = operator.post("/api/v1/cash-sessions/" + cash + "/close", Map.of("countedBalance", "64250")).expect(200).body();
        assertThat(new BigDecimal(cashClosed.get("expected_balance").asString())).isEqualByComparingTo("64250.00");
        // Closed cash cannot receive payments or be closed again.
        operator.post("/api/v1/cash-sessions/" + cash + "/close", Map.of("countedBalance", "64250")).expect(409);

        var supervisor = login(fx.user(t, "SUPERVISOR"));
        String from = Instant.now().minus(1, ChronoUnit.HOURS).toString(), to = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        var revenue = supervisor.get("/api/v1/reports/revenue?startsAt=" + from + "&endsAt=" + to).expect(200).body();
        assertThat(new BigDecimal(revenue.get("gross").asString())).isEqualByComparingTo("14250.00");
        assertThat(new BigDecimal(revenue.get("net").asString())).isEqualByComparingTo("14250.00");
        // Operators cannot read financial reports.
        operator.get("/api/v1/reports/revenue?startsAt=" + from + "&endsAt=" + to).expect(403);

        long audited = db.sql("SELECT count(*) FROM audit_logs WHERE organization_id=:o AND action IN('CHECK_IN','PAYMENT_CAPTURED','CHECK_OUT','CASH_OPENED','CASH_CLOSED')")
            .param("o", t.organizationId()).query(Long.class).single();
        assertThat(audited).isEqualTo(5L);
    }

    @Test
    void cashDifferenceRaisesAnomaly() throws Exception {
        var t = fx.tenant();
        var operator = login(fx.user(t, "OPERATOR"));
        UUID cash = UUID.fromString(operator.post("/api/v1/cash-sessions/open", Map.of("openingBalance", "10000")).expect(200).body().get("id").asString());
        operator.post("/api/v1/cash-sessions/" + cash + "/close", Map.of("countedBalance", "9000")).expect(200);
        assertThat(db.sql("SELECT count(*) FROM anomaly_alerts WHERE reference_id=:c AND type='CASH_DIFFERENCE'").param("c", cash).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void requoteUpdatesPendingOrderAmount() throws Exception {
        var t = fx.tenant();
        var operator = login(fx.user(t, "OPERATOR"));
        fx.capacity(t, "CAR", 5);
        UUID space = fx.space(t, "Q1", "CAR"), vehicle = fx.vehicle(t, null, "CAR");
        UUID session = UUID.fromString(operator.post("/api/v1/parking-sessions/check-in", Map.of("vehicleId", vehicle, "spaceId", space, "idempotencyKey", UUID.randomUUID())).expect(200).body().get("id").asString());
        db.sql("UPDATE parking_sessions SET entry_at=now()-interval '30 minutes'+interval '30 seconds' WHERE id=:id").param("id", session).update();
        var first = operator.post("/api/v1/parking-sessions/" + session + "/quote", null).expect(200).body().get("order");
        assertThat(new BigDecimal(first.get("amount").asString())).isEqualByComparingTo("4500.00");
        db.sql("UPDATE parking_sessions SET entry_at=now()-interval '150 minutes'+interval '30 seconds' WHERE id=:id").param("id", session).update();
        var second = operator.post("/api/v1/parking-sessions/" + session + "/quote", null).expect(200).body().get("order");
        assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(new BigDecimal(second.get("amount").asString())).isEqualByComparingTo("22500.00");
    }
}
