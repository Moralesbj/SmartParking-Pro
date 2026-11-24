package com.smartparking.modules.platerecognition.application;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.tenancy.Scope;
import com.smartparking.modules.vehicles.domain.Plate;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Java is the authority: a reading is auto-accepted only when every rule passes; otherwise a human reviews it. */
@Service
public class AlprService {
    public record Decision(String status, String reason) {}
    private final JdbcClient db; private final ScopedDatabase data; private final AuditService audit; private final PlateRecognitionProvider provider;
    private final BigDecimal threshold;

    public AlprService(JdbcClient db, ScopedDatabase data, AuditService audit, PlateRecognitionProvider provider,
                       @Value("${app.alpr.auto-accept-confidence:0.90}") BigDecimal threshold) {
        this.db = db; this.data = data; this.audit = audit; this.provider = provider; this.threshold = threshold;
    }

    static Optional<String> validPlate(String raw) {
        try { return Optional.of(Plate.normalize(raw, "CO")); } catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    /** Pure decision rule, unit-tested separately. */
    public static Decision decide(Optional<String> plate, BigDecimal confidence, boolean providerReview, BigDecimal threshold, Optional<Map<String, Object>> vehicle) {
        if (plate.isEmpty()) return new Decision("PENDING", "Lectura sin placa válida");
        if (providerReview) return new Decision("PENDING", "El motor ALPR marcó la lectura como ambigua");
        if (confidence == null || confidence.compareTo(threshold) < 0) return new Decision("PENDING", "Confianza por debajo del umbral " + threshold);
        if (vehicle.isEmpty()) return new Decision("PENDING", "Placa no registrada en la organización");
        if (Boolean.TRUE.equals(vehicle.get().get("watchlisted"))) return new Decision("PENDING", "Vehículo en lista de observación");
        return new Decision("AUTO_ACCEPTED", "Reglas superadas");
    }

    @Transactional
    public Map<String, Object> simulate(Scope s, UUID deviceId, PlateRecognitionProvider.SimulatedFrame frame) {
        if (deviceId != null) {
            var device = data.row(s, "devices", deviceId, false);
            if (!device.get("type").equals("CAMERA")) throw new ApiException(422, "El dispositivo no es una cámara");
        }
        var reading = provider.simulate(frame);
        var plate = reading.plate() == null ? Optional.<String>empty() : validPlate(reading.plate());
        var vehicle = plate.flatMap(p -> db.sql("SELECT id,watchlisted FROM vehicles WHERE organization_id=:org AND plate=:p AND country_code='CO' AND active")
            .param("org", s.organizationId()).param("p", p).query().listOfRows().stream().findFirst());
        var decision = decide(plate, reading.confidence(), reading.providerRequestsReview(), threshold, vehicle);
        UUID id = UUID.randomUUID();
        String warnings = String.join(",", reading.warnings());
        db.sql("""
            INSERT INTO plate_recognitions(id,organization_id,branch_id,device_id,vehicle_id,plate_candidate,confidence,mode,review_status,decision_reason,warnings)
            VALUES(:id,:org,:branch,:device,:vehicle,:plate,:confidence,:mode,:status,:reason,:warnings)
            """).param("id", id).param("org", s.organizationId()).param("branch", s.branchId()).param("device", deviceId)
            .param("vehicle", vehicle.map(v -> v.get("id")).orElse(null)).param("plate", plate.orElse(null))
            .param("confidence", reading.confidence().max(BigDecimal.ZERO).min(BigDecimal.ONE)).param("mode", reading.mode())
            .param("status", decision.status()).param("reason", decision.reason()).param("warnings", warnings.substring(0, Math.min(400, warnings.length()))).update();
        if (vehicle.isPresent() && Boolean.TRUE.equals(vehicle.get().get("watchlisted")))
            db.sql("INSERT INTO anomaly_alerts(id,organization_id,branch_id,type,detail,reference_id) VALUES(gen_random_uuid(),:org,:branch,'WATCHLIST_PLATE','Lectura de placa en observación',:ref) ON CONFLICT DO NOTHING")
                .param("org", s.organizationId()).param("branch", s.branchId()).param("ref", id).update();
        audit.record(s, "ALPR_READING_" + decision.status(), "plate_recognitions", id);
        return data.row(s, "plate_recognitions", id, false);
    }

    @Transactional
    public Map<String, Object> review(Scope s, UUID id, String status, String correctedPlate) {
        var row = data.row(s, "plate_recognitions", id, true);
        if (!row.get("review_status").equals("PENDING")) throw ApiException.conflict("Lectura ya decidida");
        String plate = null; Object vehicle = null;
        if (status.equals("CONFIRMED")) {
            plate = validPlate(correctedPlate).orElseThrow(() -> new ApiException(422, "Placa colombiana inválida"));
            vehicle = db.sql("SELECT id FROM vehicles WHERE organization_id=:org AND plate=:p AND active").param("org", s.organizationId()).param("p", plate)
                .query(UUID.class).optional().orElse(null);
        }
        boolean corrected = plate != null && !plate.equals(row.get("plate_candidate"));
        db.sql("UPDATE plate_recognitions SET review_status=:status,plate_candidate=coalesce(:plate,plate_candidate),vehicle_id=coalesce(:vehicle,vehicle_id),reviewed_by=:actor,reviewed_at=now(),decision_reason=:reason WHERE id=:id")
            .param("status", status).param("plate", plate).param("vehicle", vehicle).param("actor", s.actorId())
            .param("reason", status.equals("REJECTED") ? "Rechazada por operador" : corrected ? "Corregida por operador" : "Confirmada por operador").param("id", id).update();
        audit.record(s, corrected ? "ALPR_CORRECTED" : "ALPR_" + status, "plate_recognitions", id);
        return data.row(s, "plate_recognitions", id, false);
    }
}
