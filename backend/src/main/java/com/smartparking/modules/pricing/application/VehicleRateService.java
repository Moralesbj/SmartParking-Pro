package com.smartparking.modules.pricing.application;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.tenancy.Scope;
import com.smartparking.modules.pricing.domain.ParkingRateCalculationDTO;
import com.smartparking.modules.pricing.domain.ParkingRateRequest;
import com.smartparking.modules.vehicles.domain.VehicleCategory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-branch configuration of each vehicle category: minute rate, full rate and its block, monthly fee, courtesy time,
 * capacity and status. Every branch has exactly one row per category (provisioned by the database from a template);
 * each change is versioned by the database. Stays copy the values at entry, so later changes never touch them (RN-012).
 */
@Service
public class VehicleRateService {
    private final JdbcClient db; private final ScopedDatabase data; private final AuditService audit; private final ParkingRateService engine;

    public VehicleRateService(JdbcClient db, ScopedDatabase data, AuditService audit, ParkingRateService engine) {
        this.db = db; this.data = data; this.audit = audit; this.engine = engine;
    }

    public record Update(BigDecimal minuteRate, BigDecimal fullRate, int blockMinutes, BigDecimal monthlyFee, int graceMinutes, int capacity, boolean active) {}

    public List<Map<String, Object>> list(Scope s) {
        var rows = db.sql("""
            SELECT r.*, t.sort_order, ceil(r.full_rate / r.minute_rate)::int minutes_to_full_rate
            FROM vehicle_rates r JOIN vehicle_rate_templates t ON t.vehicle_type = r.vehicle_type
            WHERE r.organization_id = :org AND r.branch_id = :branch ORDER BY t.sort_order
            """).param("org", s.organizationId()).param("branch", s.branchId()).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : rows) {
            var category = VehicleCategory.parse((String) row.get("vehicle_type"));
            var copy = new LinkedHashMap<>(row);
            copy.put("label", category.label());
            copy.put("includes", category.includes());
            out.add(copy);
        }
        return out;
    }

    /** Row for a category in the scope's branch, optionally locked. */
    public Map<String, Object> get(Scope s, String vehicleType, boolean lock) {
        VehicleCategory.parse(vehicleType);
        return db.sql("SELECT * FROM vehicle_rates WHERE organization_id = :org AND branch_id = :branch AND vehicle_type = :type" + (lock ? " FOR UPDATE" : ""))
            .param("org", s.organizationId()).param("branch", s.branchId()).param("type", vehicleType)
            .query().listOfRows().stream().findFirst().orElseThrow(() -> ApiException.conflict("La sede no tiene configuración para " + VehicleCategory.parse(vehicleType).label()));
    }

    /** Active configuration used to admit a vehicle of this category. */
    public Map<String, Object> forEntry(Scope s, String vehicleType) {
        var rate = get(s, vehicleType, false);
        if (!Boolean.TRUE.equals(rate.get("active"))) throw ApiException.conflict(VehicleCategory.parse(vehicleType).label() + " no está habilitada en esta sede");
        return rate;
    }

    @Transactional
    public Map<String, Object> update(Scope s, String vehicleType, Update u) {
        validate(u);
        data.lockBranch(s); // same lock as check-in: capacity changes and entries are serialized per branch
        var current = get(s, vehicleType, true);
        db.sql("""
            UPDATE vehicle_rates SET minute_rate = :minute, full_rate = :full, block_minutes = :block, monthly_fee = :monthly,
              grace_minutes = :grace, capacity = :capacity, active = :active, updated_by = :actor
            WHERE id = :id
            """).param("minute", u.minuteRate()).param("full", u.fullRate()).param("block", u.blockMinutes()).param("monthly", u.monthlyFee())
            .param("grace", u.graceMinutes()).param("capacity", u.capacity()).param("active", u.active()).param("actor", s.actorId())
            .param("id", current.get("id")).update();
        audit.record(s, "VEHICLE_RATE_UPDATED", "vehicle_rates", (UUID) current.get("id"));
        return get(s, vehicleType, false);
    }

    static void validate(Update u) {
        if (u.minuteRate() == null || u.fullRate() == null || u.monthlyFee() == null) throw new ApiException(422, "Complete todos los valores");
        for (var amount : List.of(u.minuteRate(), u.fullRate(), u.monthlyFee()))
            if (amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(new BigDecimal("999999999999")) > 0) throw new ApiException(422, "Los valores deben ser positivos y con máximo dos decimales");
        if (u.fullRate().compareTo(u.minuteRate()) < 0) throw new ApiException(422, "La tarifa plena no puede ser menor que la tarifa por minuto");
        if (u.blockMinutes() < 1 || u.blockMinutes() > 10080) throw new ApiException(422, "La duración de la tarifa plena debe estar entre 1 minuto y 7 días");
        if (u.graceMinutes() < 0 || u.graceMinutes() > 1440 || u.graceMinutes() >= u.blockMinutes()) throw new ApiException(422, "El tiempo de cortesía debe ser menor que la duración del bloque");
        if (u.capacity() < 0 || u.capacity() > 100000) throw new ApiException(422, "Capacidad inválida");
    }

    public List<Map<String, Object>> history(Scope s, String vehicleType) {
        VehicleCategory.parse(vehicleType);
        return db.sql("""
            SELECT h.version, h.minute_rate, h.full_rate, h.block_minutes, h.monthly_fee, h.grace_minutes, h.capacity, h.active, h.changed_at,
                   u.full_name changed_by_name
            FROM vehicle_rate_versions h LEFT JOIN users u ON u.id = h.changed_by
            WHERE h.organization_id = :org AND h.branch_id = :branch AND h.vehicle_type = :type ORDER BY h.version DESC LIMIT 50
            """).param("org", s.organizationId()).param("branch", s.branchId()).param("type", vehicleType).query().listOfRows();
    }

    /** Monthly fee of a category in the scope's branch; used when a plan does not set its own price. */
    public BigDecimal monthlyFee(Scope s, String vehicleType) { return (BigDecimal) get(s, vehicleType, false).get("monthly_fee"); }

    /** Simulator: what would this stay cost with the branch's current configuration? Read-only. */
    public ParkingRateCalculationDTO simulate(Scope s, String vehicleType, Instant entry, Instant exit) {
        if (entry == null || exit == null || exit.isBefore(entry) || Duration.between(entry, exit).toDays() > 366) throw new ApiException(422, "Intervalo inválido (máximo 366 días)");
        var rate = get(s, vehicleType, false);
        return engine.calculate(ParkingRateRequest.of(entry, exit, vehicleType, (BigDecimal) rate.get("minute_rate"), (BigDecimal) rate.get("full_rate"),
            (int) rate.get("block_minutes"), (int) rate.get("grace_minutes")));
    }
}
