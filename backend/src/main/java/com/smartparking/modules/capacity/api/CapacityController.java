package com.smartparking.modules.capacity.api;

import com.smartparking.common.tenancy.ScopeService;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

/**
 * M06: live capacity. A space's effective state is derived, never stored twice:
 * BLOCKED/MAINTENANCE from configuration, OCCUPIED from an open session, RESERVED from a reservation covering now.
 */
@RestController
@RequestMapping("/api/v1/spaces")
public class CapacityController {
    private static final String STATE_SQL = """
        SELECT p.id,p.code,p.type,p.zone_id,z.name zone_name,z.level,
          CASE WHEN p.status<>'AVAILABLE' THEN p.status
               WHEN EXISTS(SELECT 1 FROM parking_sessions x WHERE x.space_id=p.id AND x.status='OPEN') THEN 'OCCUPIED'
               WHEN EXISTS(SELECT 1 FROM reservations r WHERE r.space_id=p.id AND r.status='CONFIRMED' AND r.starts_at<=now() AND r.ends_at>now()) THEN 'RESERVED'
               ELSE 'AVAILABLE' END state
        FROM parking_spaces p LEFT JOIN parking_zones z ON z.id=p.zone_id
        WHERE p.organization_id=:org AND p.branch_id=:branch AND p.active
        """;
    private final JdbcClient db; private final ScopeService scopes;
    public CapacityController(JdbcClient db, ScopeService scopes) { this.db = db; this.scopes = scopes; }

    /**
     * Capacity per vehicle category: the configured capacity minus open stays and reservations in force now.
     * "unavailable" counts physical spaces of that category out of service (blocked or in maintenance).
     */
    @GetMapping("/availability")
    public Map<String, Object> availability() {
        var s = scopes.current(); // customers may check availability too; data is aggregate-only
        var rows = db.sql("""
            SELECT r.vehicle_type type, r.capacity, r.active,
              (SELECT count(*) FROM parking_sessions x WHERE x.branch_id=r.branch_id AND x.status='OPEN' AND x.vehicle_type=r.vehicle_type) occupied,
              (SELECT count(*) FROM reservations v JOIN vehicles rv ON rv.id=v.vehicle_id WHERE v.branch_id=r.branch_id AND v.status='CONFIRMED'
                 AND v.starts_at<=now() AND v.ends_at>now() AND rv.type=r.vehicle_type) reserved,
              (SELECT count(*) FROM parking_spaces p WHERE p.branch_id=r.branch_id AND p.active AND p.type=r.vehicle_type) spaces,
              (SELECT count(*) FROM parking_spaces p WHERE p.branch_id=r.branch_id AND p.active AND p.type=r.vehicle_type AND p.status<>'AVAILABLE') unavailable
            FROM vehicle_rates r JOIN vehicle_rate_templates t ON t.vehicle_type=r.vehicle_type
            WHERE r.organization_id=:org AND r.branch_id=:branch ORDER BY t.sort_order
            """).param("org", s.organizationId()).param("branch", s.branchId()).query().listOfRows();
        List<Map<String, Object>> types = new ArrayList<>();
        for (var r : rows) {
            long capacity = ((Number) r.get("capacity")).longValue(), occupied = ((Number) r.get("occupied")).longValue(), reserved = ((Number) r.get("reserved")).longValue();
            types.add(Map.of("type", r.get("type"), "active", r.get("active"), "total", capacity, "occupied", occupied, "reserved", reserved,
                "available", Boolean.TRUE.equals(r.get("active")) ? Math.max(0, capacity - occupied - reserved) : 0,
                "spaces", ((Number) r.get("spaces")).longValue(), "unavailable", ((Number) r.get("unavailable")).longValue()));
        }
        return Map.of("types", types);
    }

    @GetMapping("/map")
    public List<Map<String, Object>> map() {
        var s = scopes.staff();
        var rows = db.sql(STATE_SQL + " ORDER BY z.level NULLS LAST,z.name NULLS LAST,p.code").param("org", s.organizationId()).param("branch", s.branchId()).query().listOfRows();
        Map<String, Map<String, Object>> zones = new LinkedHashMap<>();
        for (var r : rows) {
            String key = String.valueOf(r.get("zone_id"));
            var zone = zones.computeIfAbsent(key, k -> new LinkedHashMap<>(Map.of("zoneId", key, "name", r.get("zone_name") == null ? "Sin zona" : r.get("zone_name"),
                "level", r.get("level") == null ? "" : r.get("level"), "spaces", new ArrayList<Map<String, Object>>())));
            @SuppressWarnings("unchecked") var spaces = (List<Map<String, Object>>) zone.get("spaces");
            spaces.add(Map.of("id", r.get("id"), "code", r.get("code"), "type", r.get("type"), "state", r.get("state")));
        }
        return new ArrayList<>(zones.values());
    }
}
