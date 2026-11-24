package com.smartparking.modules.workforce.api;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.tenancy.ScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** M13: shift scheduling (no overlaps per operator, DB exclusion), attendance window, cancellation and incidents. */
@RestController @RequestMapping("/api/v1/operator-shifts")
public class WorkforceController {
    static final Duration EARLY_START = Duration.ofMinutes(30);
    static final Duration MAX_SHIFT = Duration.ofHours(16);
    private final JdbcClient db; private final ScopedDatabase data; private final ScopeService scopes; private final AuditService audit;

    public WorkforceController(JdbcClient db, ScopedDatabase data, ScopeService scopes, AuditService audit) { this.db = db; this.data = data; this.scopes = scopes; this.audit = audit; }

    public record Shift(@NotNull UUID operatorId, @NotNull Instant scheduledStart, @NotNull Instant scheduledEnd) {}
    public record Incident(@NotBlank @Pattern(regexp = "LATE|ABSENCE|HANDOVER|INCIDENT") String kind, @NotBlank @Size(max = 500) String detail) {}

    @GetMapping public List<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var s = scopes.staff(); int limit = Math.clamp(size, 1, 100);
        // Operators see only their own shifts; supervisors see the branch.
        return db.sql("SELECT * FROM operator_shifts WHERE organization_id=:org AND branch_id=:branch AND (:all OR operator_id=:actor) ORDER BY scheduled_start DESC LIMIT :l OFFSET :o")
            .param("org", s.organizationId()).param("branch", s.branchId()).param("all", s.supervisor()).param("actor", s.actorId())
            .param("l", limit).param("o", Math.max(0, page) * limit).query().listOfRows();
    }

    @PostMapping @Transactional public Map<String, Object> create(@Valid @RequestBody Shift b) {
        var s = scopes.supervisor();
        if (!b.scheduledEnd().isAfter(b.scheduledStart()) || Duration.between(b.scheduledStart(), b.scheduledEnd()).compareTo(MAX_SHIFT) > 0)
            throw new ApiException(422, "Horario inválido (máximo 16 horas)");
        long membership = db.sql("SELECT count(*) FROM user_memberships WHERE user_id=:user AND organization_id=:org AND (branch_id IS NULL OR branch_id=:branch) AND active AND role IN('OPERATOR','SUPERVISOR')")
            .param("user", b.operatorId()).param("org", s.organizationId()).param("branch", s.branchId()).query(Long.class).single();
        if (membership == 0) throw new ApiException(422, "La persona no es operador ni supervisor de esta sede");
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO operator_shifts(id,organization_id,branch_id,operator_id,scheduled_start,scheduled_end) VALUES(:id,:org,:branch,:op,:starts,:ends)")
            .param("id", id).param("org", s.organizationId()).param("branch", s.branchId()).param("op", b.operatorId())
            .param("starts", Timestamp.from(b.scheduledStart())).param("ends", Timestamp.from(b.scheduledEnd())).update();
        audit.record(s, "SHIFT_SCHEDULED", "operator_shifts", id);
        return data.row(s, "operator_shifts", id, false);
    }

    @PostMapping("/{id}/{action:start|end|cancel}") @Transactional public Map<String, Object> transition(@PathVariable UUID id, @PathVariable String action) {
        var s = scopes.staff(); data.lockBranch(s);
        var row = data.row(s, "operator_shifts", id, true);
        boolean own = row.get("operator_id").equals(s.actorId());
        String status = (String) row.get("status");
        Instant now = Instant.now(), start = ((Timestamp) row.get("scheduled_start")).toInstant(), end = ((Timestamp) row.get("scheduled_end")).toInstant();
        switch (action) {
            case "start" -> {
                if (!own && !s.supervisor()) throw new ApiException(403, "Turno de otro operador");
                if (!status.equals("SCHEDULED")) throw ApiException.conflict("Transición de turno inválida");
                if (now.isBefore(start.minus(EARLY_START)) || !now.isBefore(end)) throw ApiException.conflict("Fuera de la ventana del turno");
                if (now.isAfter(start.plus(Duration.ofMinutes(10)))) incident(s, id, "LATE", "Inicio registrado tarde");
                db.sql("UPDATE operator_shifts SET status='ACTIVE',actual_start=now(),updated_at=now() WHERE id=:id").param("id", id).update();
            }
            case "end" -> {
                if (!own && !s.supervisor()) throw new ApiException(403, "Turno de otro operador");
                if (!status.equals("ACTIVE")) throw ApiException.conflict("Transición de turno inválida");
                db.sql("UPDATE operator_shifts SET status='ENDED',actual_end=now(),updated_at=now() WHERE id=:id").param("id", id).update();
            }
            default -> {
                if (!s.supervisor()) throw new ApiException(403, "Cancelar turnos requiere supervisión");
                if (!status.equals("SCHEDULED")) throw ApiException.conflict("Solo se cancelan turnos programados");
                db.sql("UPDATE operator_shifts SET status='CANCELLED',updated_at=now() WHERE id=:id").param("id", id).update();
            }
        }
        audit.record(s, "SHIFT_" + action.toUpperCase(Locale.ROOT), "operator_shifts", id);
        return data.row(s, "operator_shifts", id, false);
    }

    private void incident(com.smartparking.common.tenancy.Scope s, UUID shift, String kind, String detail) {
        db.sql("INSERT INTO shift_incidents(id,organization_id,branch_id,shift_id,kind,detail,reported_by) VALUES(gen_random_uuid(),:o,:b,:sh,:k,:d,:a)")
            .param("o", s.organizationId()).param("b", s.branchId()).param("sh", shift).param("k", kind).param("d", detail).param("a", s.actorId()).update();
    }

    @PostMapping("/{id}/incidents") @Transactional public Map<String, Object> report(@PathVariable UUID id, @Valid @RequestBody Incident b) {
        var s = scopes.staff(); var row = data.row(s, "operator_shifts", id, false);
        if (!row.get("operator_id").equals(s.actorId()) && !s.supervisor()) throw new ApiException(403, "Turno de otro operador");
        incident(s, id, b.kind(), b.detail());
        audit.record(s, "SHIFT_INCIDENT", "operator_shifts", id);
        return Map.of("shiftId", id, "kind", b.kind());
    }

    @GetMapping("/{id}/incidents") public List<Map<String, Object>> incidents(@PathVariable UUID id) {
        var s = scopes.staff(); var row = data.row(s, "operator_shifts", id, false);
        if (!row.get("operator_id").equals(s.actorId()) && !s.supervisor()) throw new ApiException(403, "Turno de otro operador");
        return db.sql("SELECT kind,detail,reported_by,created_at FROM shift_incidents WHERE shift_id=:id ORDER BY created_at").param("id", id).query().listOfRows();
    }
}
