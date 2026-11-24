package com.smartparking.modules.parking.api;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.tenancy.ScopeService;
import com.smartparking.modules.parking.application.ParkingService;
import com.smartparking.modules.vehicles.domain.VehicleCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/parking-sessions")
public class ParkingController {
    private final ParkingService parking; private final ScopeService scopes; private final ScopedDatabase data; private final JdbcClient db;
    public ParkingController(ParkingService parking, ScopeService scopes, ScopedDatabase data, JdbcClient db) { this.parking = parking; this.scopes = scopes; this.data = data; this.db = db; }

    /** spaceId is optional: capacity is controlled per vehicle category. */
    public record CheckIn(@NotNull UUID vehicleId, UUID spaceId, @NotNull UUID idempotencyKey) {}
    public record Discount(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount, @NotBlank @Size(max = 400) String reason, @NotNull UUID idempotencyKey) {}

    /** Most recent first, with plate and category for the operator's list. */
    @GetMapping public List<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var s = scopes.staff(); int limit = Math.clamp(size, 1, 100);
        return db.sql("""
            SELECT p.id,p.ticket_number,p.ticket_code,v.plate,p.vehicle_type,p.space_id,p.entry_at,p.exit_at,p.status,p.final_amount,(p.subscription_id IS NOT NULL) monthly_customer
            FROM parking_sessions p JOIN vehicles v ON v.id=p.vehicle_id
            WHERE p.organization_id=:org AND p.branch_id=:branch ORDER BY (p.status='OPEN') DESC, p.entry_at DESC LIMIT :limit OFFSET :offset
            """).param("org", s.organizationId()).param("branch", s.branchId()).param("limit", limit).param("offset", Math.max(0, page) * limit).query().listOfRows();
    }
    @PostMapping("/check-in") public Map<String, Object> checkIn(@Valid @RequestBody CheckIn body) { return parking.checkIn(scopes.staff(), body.vehicleId(), body.spaceId(), body.idempotencyKey(), null); }
    /** Ticket lookup by plate, six-digit code or QR content. */
    @GetMapping("/lookup") public Map<String, Object> lookup(@RequestParam String q) { return parking.lookup(scopes.staff(), q); }
    @GetMapping("/{id}/liquidation") public Map<String, Object> liquidation(@PathVariable UUID id) { return parking.liquidation(scopes.staff(), id); }
    @PostMapping("/{id}/quote") public Map<String, Object> quote(@PathVariable UUID id) { return parking.quote(scopes.staff(), id); }
    @PostMapping("/{id}/discounts") public Map<String, Object> discount(@PathVariable UUID id, @Valid @RequestBody Discount b) { return parking.discount(scopes.supervisor(), id, b.amount(), b.reason(), b.idempotencyKey()); }
    @PostMapping("/{id}/check-out") public Map<String, Object> checkOut(@PathVariable UUID id) { return parking.checkOut(scopes.staff(), id); }
    @GetMapping("/{id}/ticket") public Map<String, Object> ticket(@PathVariable UUID id) {
        var s = scopes.staff(); var session = data.row(s, "parking_sessions", id, false);
        var branch = db.sql("SELECT name,address FROM branches WHERE id=:b AND organization_id=:o").param("b", s.branchId()).param("o", s.organizationId()).query().singleRow();
        String plate = db.sql("SELECT plate FROM vehicles WHERE id=:id AND organization_id=:o").param("id", session.get("vehicle_id")).param("o", s.organizationId()).query(String.class).single();
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("kind", "TICKET_INTERNO");
        ticket.put("ticketNumber", session.get("ticket_number"));
        ticket.put("ticketCode", session.get("ticket_code"));
        ticket.put("qrPayload", ParkingService.QR_PREFIX + id);
        ticket.put("plate", plate);
        ticket.put("vehicleTypeLabel", VehicleCategory.parse((String) session.get("vehicle_type")).label());
        ticket.put("entryAt", session.get("entry_at"));
        ticket.put("branch", branch.get("name"));
        ticket.put("address", branch.get("address"));
        ticket.put("session", session);
        ticket.put("notice", "No es una factura electrónica DIAN");
        return ticket;
    }
}
