package com.smartparking.modules.customerportal.api;

import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.tenancy.ScopeService;
import com.smartparking.modules.reservations.application.ReservationService;
import com.smartparking.modules.vehicles.domain.Plate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/portal")
public class PortalController {
    private final JdbcClient db;private final ScopeService scopes;private final ReservationService reservations;private final AuditService audit;
    public PortalController(JdbcClient db,ScopeService scopes,ReservationService reservations,AuditService audit) { this.db=db;this.scopes=scopes;this.reservations=reservations;this.audit=audit; }
    @GetMapping("/me") public Map<String,Object> me() {
        var s=scopes.current();UUID customer=reservations.ownCustomer(s);
        return db.sql("SELECT id,name,email,phone,consent FROM customers WHERE id=:id AND organization_id=:org").param("id",customer).param("org",s.organizationId()).query().singleRow();
    }
    @GetMapping("/{resource:vehicles|reservations|subscriptions|payments|history}") public List<Map<String,Object>> list(@PathVariable String resource,@RequestParam(defaultValue="0") int page) {
        var s=scopes.current();UUID customer=reservations.ownCustomer(s);
        String sql=switch(resource) {
            case "vehicles" -> "SELECT v.* FROM vehicles v WHERE v.organization_id=:org AND v.customer_id=:customer ORDER BY v.created_at DESC LIMIT 20 OFFSET :offset";
            case "payments" -> "SELECT p.* FROM payments p JOIN payment_orders o ON o.id=p.order_id WHERE p.organization_id=:org AND p.branch_id=:branch AND o.customer_id=:customer ORDER BY p.received_at DESC LIMIT 20 OFFSET :offset";
            case "history" -> "SELECT p.* FROM parking_sessions p JOIN vehicles v ON v.id=p.vehicle_id WHERE p.organization_id=:org AND p.branch_id=:branch AND v.customer_id=:customer ORDER BY p.entry_at DESC LIMIT 20 OFFSET :offset";
            default -> "SELECT * FROM "+resource+" WHERE organization_id=:org AND branch_id=:branch AND customer_id=:customer ORDER BY created_at DESC LIMIT 20 OFFSET :offset";
        };
        return db.sql(sql).param("org",s.organizationId()).param("branch",s.branchId()).param("customer",customer).param("offset",Math.max(0,page)*20).query().listOfRows();
    }
    public record Vehicle(@NotBlank String plate,@Pattern(regexp=com.smartparking.modules.vehicles.domain.VehicleCategory.PATTERN) @NotBlank String type) {}
    @PostMapping("/vehicles") @Transactional public Map<String,UUID> vehicle(@Valid @RequestBody Vehicle b) {
        var s=scopes.current();UUID customer=reservations.ownCustomer(s),id=UUID.randomUUID();String plate=Plate.normalize(b.plate(),"CO",b.type());
        db.sql("INSERT INTO vehicles(id,organization_id,customer_id,plate,type) VALUES(:id,:org,:customer,:plate,:type)")
            .param("id",id).param("org",s.organizationId()).param("customer",customer).param("plate",plate).param("type",b.type()).update();audit.record(s,"PORTAL_VEHICLE_CREATED","vehicles",id);return Map.of("id",id);
    }
}
