package com.smartparking.flows;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.modules.devices.application.DeviceAdapter;
import com.smartparking.modules.platerecognition.application.AlprService;
import com.smartparking.support.Fixtures;
import com.smartparking.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Flow D: simulated camera → reading → Java rules → human review when ambiguous → barrier only with authorization. */
class AlprDeviceFlowIT extends IntegrationTest {
    @Autowired DeviceAdapter.Simulator simulator;

    private UUID device(Fixtures.Tenant t, String type) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO devices(id,organization_id,branch_id,name,type) VALUES(:id,:o,:b,:n,:t)")
            .param("id", id).param("o", t.organizationId()).param("b", t.branchId()).param("n", type + " entrada").param("t", type).update();
        return id;
    }

    private String plateOf(UUID vehicle) { return db.sql("SELECT plate FROM vehicles WHERE id=:id").param("id", vehicle).query(String.class).single(); }

    @Test
    void highConfidenceKnownPlateIsAutoAcceptedAndOpensBarrier() throws Exception {
        var t = fx.tenant();
        var op = login(fx.user(t, "OPERATOR"));
        UUID camera = device(t, "CAMERA"), barrier = device(t, "BARRIER");
        String plate = plateOf(fx.vehicle(t, null, "CAR"));
        var reading = op.post("/api/v1/plate-recognitions/simulate", Map.of("plate", plate, "confidence", "0.97", "deviceId", camera)).expect(200).body();
        assertThat(reading.get("review_status").asString()).isEqualTo("AUTO_ACCEPTED");
        assertThat(reading.get("mode").asString()).isEqualTo("SIMULATOR");

        // Offline barrier refuses commands.
        op.post("/api/v1/devices/" + barrier + "/commands", Map.of("command", "OPEN", "recognitionId", reading.get("id").asString())).expect(409);
        op.post("/api/v1/devices/" + barrier + "/heartbeat", null).expect(200);
        var cmd = op.post("/api/v1/devices/" + barrier + "/commands", Map.of("command", "OPEN", "recognitionId", reading.get("id").asString())).expect(200).body();
        assertThat(cmd.get("status").asString()).isEqualTo("SIMULATED_ACK");
        assertThat(cmd.get("hardwareActivated").asBoolean()).isFalse();
        assertThat(db.sql("SELECT count(*) FROM device_commands WHERE device_id=:d").param("d", barrier).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void lowConfidenceNeverOpensBarrierUntilHumanConfirms() throws Exception {
        var t = fx.tenant();
        var op = login(fx.user(t, "OPERATOR"));
        UUID barrier = device(t, "BARRIER");
        op.post("/api/v1/devices/" + barrier + "/heartbeat", null).expect(200);
        String plate = plateOf(fx.vehicle(t, null, "CAR"));
        var reading = op.post("/api/v1/plate-recognitions/simulate", Map.of("plate", plate, "confidence", "0.55")).expect(200).body();
        assertThat(reading.get("review_status").asString()).isEqualTo("PENDING");
        String rid = reading.get("id").asString();
        var refused = op.post("/api/v1/devices/" + barrier + "/commands", Map.of("command", "OPEN", "recognitionId", rid)).expect(409).body();
        assertThat(refused.get("code").asString()).isEqualTo("RECOGNITION_NOT_ACCEPTED");
        // Operator cannot open manually; only a supervisor with a reason can.
        op.post("/api/v1/devices/" + barrier + "/commands", Map.of("command", "OPEN")).expect(403);

        var reviewed = op.post("/api/v1/plate-recognitions/" + rid + "/review", Map.of("status", "CONFIRMED", "correctedPlate", plate)).expect(200).body();
        assertThat(reviewed.get("review_status").asString()).isEqualTo("CONFIRMED");
        op.post("/api/v1/plate-recognitions/" + rid + "/review", Map.of("status", "REJECTED")).expect(409);
        op.post("/api/v1/devices/" + barrier + "/commands", Map.of("command", "OPEN", "recognitionId", rid)).expect(200);

        var sup = login(fx.user(t, "SUPERVISOR"));
        sup.post("/api/v1/devices/" + barrier + "/commands", Map.of("command", "OPEN")).expect(422);
        sup.post("/api/v1/devices/" + barrier + "/commands", Map.of("command", "OPEN", "reason", "Falla del lector; usuario verificado")).expect(200);
        assertThat(db.sql("SELECT count(*) FROM audit_logs WHERE organization_id=:o AND action='DEVICE_COMMAND_OPEN'").param("o", t.organizationId()).query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void unknownAndWatchlistedPlatesRequireReview() throws Exception {
        var t = fx.tenant();
        var op = login(fx.user(t, "OPERATOR"));
        var unknown = op.post("/api/v1/plate-recognitions/simulate", Map.of("plate", "ZZZ999", "confidence", "0.99")).expect(200).body();
        assertThat(unknown.get("review_status").asString()).isEqualTo("PENDING");
        UUID v = fx.vehicle(t, null, "CAR");
        db.sql("UPDATE vehicles SET watchlisted=true WHERE id=:id").param("id", v).update();
        var watched = op.post("/api/v1/plate-recognitions/simulate", Map.of("plate", plateOf(v), "confidence", "0.99")).expect(200).body();
        assertThat(watched.get("review_status").asString()).isEqualTo("PENDING");
        assertThat(db.sql("SELECT count(*) FROM anomaly_alerts WHERE type='WATCHLIST_PLATE' AND reference_id=:r").param("r", UUID.fromString(watched.get("id").asString())).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void deviceTimeoutIsRecordedAndAlerted() throws Exception {
        var t = fx.tenant();
        var sup = login(fx.user(t, "SUPERVISOR"));
        UUID barrier = device(t, "BARRIER");
        sup.post("/api/v1/devices/" + barrier + "/heartbeat", null).expect(200);
        simulator.setSimulatedLatencyMillis(4_000);
        try {
            var res = sup.post("/api/v1/devices/" + barrier + "/commands", Map.of("command", "OPEN", "reason", "Prueba de timeout")).expect(200).body();
            assertThat(res.get("status").asString()).isEqualTo("TIMEOUT");
        } finally { simulator.setSimulatedLatencyMillis(0); }
        assertThat(db.sql("SELECT count(*) FROM anomaly_alerts WHERE organization_id=:o AND type='DEVICE_COMMAND_FAILED'").param("o", t.organizationId()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void decisionRules() {
        var th = new BigDecimal("0.90");
        var known = Optional.<Map<String, Object>>of(Map.of("id", UUID.randomUUID(), "watchlisted", false));
        assertThat(AlprService.decide(Optional.of("ABC123"), new BigDecimal("0.95"), false, th, known).status()).isEqualTo("AUTO_ACCEPTED");
        assertThat(AlprService.decide(Optional.of("ABC123"), new BigDecimal("0.95"), true, th, known).status()).isEqualTo("PENDING");
        assertThat(AlprService.decide(Optional.of("ABC123"), new BigDecimal("0.899"), false, th, known).status()).isEqualTo("PENDING");
        assertThat(AlprService.decide(Optional.empty(), BigDecimal.ONE, false, th, known).status()).isEqualTo("PENDING");
    }
}
