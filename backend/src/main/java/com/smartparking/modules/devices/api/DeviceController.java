package com.smartparking.modules.devices.api;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.tenancy.*;
import com.smartparking.modules.devices.application.DeviceAdapter;
import com.smartparking.modules.platerecognition.application.AlprService;
import com.smartparking.modules.platerecognition.application.PlateRecognitionProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class DeviceController {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration RECOGNITION_VALIDITY = Duration.ofMinutes(5);
    private final JdbcClient db; private final ScopedDatabase data; private final ScopeService scopes; private final AuditService audit;
    private final DeviceAdapter adapter; private final AlprService alpr;

    public DeviceController(JdbcClient db, ScopedDatabase data, ScopeService scopes, AuditService audit, DeviceAdapter adapter, AlprService alpr) {
        this.db = db; this.data = data; this.scopes = scopes; this.audit = audit; this.adapter = adapter; this.alpr = alpr;
    }

    public record Command(@NotBlank @Pattern(regexp = "OPEN|CLOSE") String command, UUID recognitionId, @Size(max = 300) String reason) {}
    public record Simulation(@NotBlank @Size(max = 10) String plate, @DecimalMin("0") @DecimalMax("1") BigDecimal confidence, UUID deviceId,
                             @DecimalMin("0") @DecimalMax("120") Double noise, @Min(0) @Max(6) Integer blur, @DecimalMin("-15") @DecimalMax("15") Double angle, Boolean occlude) {}
    public record Review(@NotBlank @Pattern(regexp = "CONFIRMED|REJECTED") String status, @Size(max = 10) String correctedPlate) {}

    private void event(Scope s, UUID device, String type) {
        db.sql("INSERT INTO device_events(id,organization_id,branch_id,device_id,event_type,actor_id) VALUES(:id,:org,:branch,:device,:type,:actor)")
            .param("id", UUID.randomUUID()).param("org", s.organizationId()).param("branch", s.branchId()).param("device", device).param("type", type).param("actor", s.actorId()).update();
    }

    @GetMapping("/devices/{id}/health")
    public Map<String, Object> health(@PathVariable UUID id) {
        var s = scopes.staff(); var d = data.row(s, "devices", id, false);
        var events = db.sql("SELECT event_type,occurred_at FROM device_events WHERE device_id=:id ORDER BY occurred_at DESC LIMIT 20").param("id", id).query().listOfRows();
        var commands = db.sql("SELECT command,status,reason,requested_at FROM device_commands WHERE device_id=:id ORDER BY requested_at DESC LIMIT 20").param("id", id).query().listOfRows();
        return Map.of("device", d, "mode", "SIMULATOR", "events", events, "commands", commands);
    }

    @PostMapping("/devices/{id}/heartbeat") @Transactional
    public Map<String, Object> heartbeat(@PathVariable UUID id) {
        var s = scopes.staff(); data.row(s, "devices", id, true);
        db.sql("UPDATE devices SET status='ONLINE',last_heartbeat_at=now(),updated_at=now() WHERE id=:id").param("id", id).update();
        event(s, id, "SIMULATED_HEARTBEAT");
        return data.row(s, "devices", id, false);
    }

    @PostMapping("/devices/{id}/commands") @Transactional
    public Map<String, Object> command(@PathVariable UUID id, @Valid @RequestBody Command b) {
        var s = scopes.staff(); var device = data.row(s, "devices", id, true);
        if (!device.get("type").equals("BARRIER")) throw new ApiException(422, "Solo las barreras aceptan comandos");
        if (!device.get("status").equals("ONLINE")) throw ApiException.conflict("Barrera desconectada; no se envían comandos");
        if (b.command().equals("OPEN")) authorizeOpen(s, b);
        var outcome = adapter.send(id, b.command(), COMMAND_TIMEOUT);
        UUID ref = UUID.randomUUID();
        db.sql("INSERT INTO device_commands(id,organization_id,branch_id,device_id,command,status,recognition_id,reason,requested_by,completed_at) VALUES(:id,:org,:branch,:device,:cmd,:status,:rec,:reason,:actor,now())")
            .param("id", ref).param("org", s.organizationId()).param("branch", s.branchId()).param("device", id).param("cmd", b.command())
            .param("status", outcome.name()).param("rec", b.recognitionId()).param("reason", b.reason() == null ? "" : b.reason()).param("actor", s.actorId()).update();
        event(s, id, "COMMAND_" + b.command() + "_" + outcome.name());
        audit.record(s, "DEVICE_COMMAND_" + b.command(), "device_commands", ref);
        if (outcome != DeviceAdapter.Outcome.SIMULATED_ACK)
            db.sql("INSERT INTO anomaly_alerts(id,organization_id,branch_id,type,detail,reference_id) VALUES(gen_random_uuid(),:org,:branch,'DEVICE_COMMAND_FAILED','Comando sin confirmación del dispositivo',:ref)")
                .param("org", s.organizationId()).param("branch", s.branchId()).param("ref", ref).update();
        return Map.of("id", ref, "mode", "SIMULATOR", "command", b.command(), "status", outcome.name(), "hardwareActivated", false);
    }

    /** Opening needs either an accepted, recent plate reading or a supervisor with a written reason. */
    private void authorizeOpen(Scope s, Command b) {
        if (b.recognitionId() != null) {
            var r = data.row(s, "plate_recognitions", b.recognitionId(), false);
            String status = (String) r.get("review_status");
            if (!status.equals("AUTO_ACCEPTED") && !status.equals("CONFIRMED"))
                throw new ApiException(409, "La lectura no está aceptada; revísela antes de abrir", "RECOGNITION_NOT_ACCEPTED");
            if (((Timestamp) r.get("created_at")).toInstant().plus(RECOGNITION_VALIDITY).isBefore(Instant.now()))
                throw new ApiException(409, "La lectura venció; tome una nueva", "RECOGNITION_EXPIRED");
            return;
        }
        if (!s.supervisor()) throw new ApiException(403, "Apertura manual requiere supervisión", "MANUAL_OPEN_REQUIRES_SUPERVISOR");
        if (b.reason() == null || b.reason().isBlank()) throw new ApiException(422, "Indique el motivo de la apertura manual");
    }

    @GetMapping("/plate-recognitions")
    public List<Map<String, Object>> readings(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var s = scopes.staff(); int limit = Math.clamp(size, 1, 100);
        return db.sql("SELECT * FROM plate_recognitions WHERE organization_id=:org AND branch_id=:branch ORDER BY created_at DESC LIMIT :l OFFSET :o")
            .param("org", s.organizationId()).param("branch", s.branchId()).param("l", limit).param("o", Math.max(0, page) * limit).query().listOfRows();
    }

    @PostMapping("/plate-recognitions/simulate")
    public Map<String, Object> simulate(@Valid @RequestBody Simulation b) {
        var frame = new PlateRecognitionProvider.SimulatedFrame(b.plate(), b.confidence(), b.noise() == null ? 0 : b.noise(),
            b.blur() == null ? 0 : b.blur(), b.angle() == null ? 0 : b.angle(), Boolean.TRUE.equals(b.occlude()));
        return alpr.simulate(scopes.staff(), b.deviceId(), frame);
    }

    @PostMapping("/plate-recognitions/{id}/review")
    public Map<String, Object> review(@PathVariable UUID id, @Valid @RequestBody Review b) {
        return alpr.review(scopes.staff(), id, b.status(), b.correctedPlate());
    }
}
