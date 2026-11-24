package com.smartparking.modules.analytics.api;

import com.smartparking.common.tenancy.ScopeService;
import com.smartparking.common.errors.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/reports")
public class ReportingController {
    private final JdbcClient db;private final ScopeService scopes;
    public ReportingController(JdbcClient db,ScopeService scopes) { this.db=db;this.scopes=scopes; }
    @GetMapping("/occupancy") public Map<String,Object> occupancy() {
        var s=scopes.staff();
        var rows=db.sql("""
            SELECT p.id,p.code,p.type,p.status,p.zone_id,(x.id IS NOT NULL) occupied,x.id session_id
            FROM parking_spaces p LEFT JOIN parking_sessions x ON x.space_id=p.id AND x.status='OPEN'
            WHERE p.organization_id=:org AND p.branch_id=:branch AND p.active ORDER BY p.code
            """).param("org",s.organizationId()).param("branch",s.branchId()).query().listOfRows();
        return Map.of("spaces",rows,"total",rows.size(),"occupied",rows.stream().filter(r -> Boolean.TRUE.equals(r.get("occupied"))).count(),"blocked",rows.stream().filter(r -> !r.get("status").equals("AVAILABLE")).count());
    }
    @GetMapping("/revenue") public Map<String,Object> revenue(@RequestParam Instant startsAt,@RequestParam Instant endsAt) {
        var s=scopes.supervisor();validate(startsAt,endsAt);
        Map<String,Object> params=Map.of("org",s.organizationId(),"branch",s.branchId(),"starts",Timestamp.from(startsAt),"ends",Timestamp.from(endsAt));
        var rows=db.sql("SELECT method,sum(amount) total,count(*) transactions FROM payments WHERE organization_id=:org AND branch_id=:branch AND received_at>=:starts AND received_at<:ends GROUP BY method ORDER BY method").params(params).query().listOfRows();
        var refunded=db.sql("SELECT coalesce(sum(amount),0) FROM refunds WHERE organization_id=:org AND branch_id=:branch AND created_at>=:starts AND created_at<:ends").params(params).query(BigDecimal.class).single();
        BigDecimal gross=rows.stream().map(r -> (BigDecimal)r.get("total")).reduce(BigDecimal.ZERO,BigDecimal::add);
        return Map.of("byMethod",rows,"gross",gross,"refunds",refunded,"net",gross.subtract(refunded));
    }
    private void validate(Instant start,Instant end) { if(!end.isAfter(start) || Duration.between(start,end).toDays()>366) throw new ApiException(422,"Rango inválido; máximo 366 días"); }
    @GetMapping("/reservations") public List<Map<String,Object>> reservations(@RequestParam Instant startsAt,@RequestParam Instant endsAt) {
        var s=scopes.supervisor();validate(startsAt,endsAt);
        return db.sql("SELECT status,count(*) total FROM reservations WHERE organization_id=:org AND branch_id=:branch AND starts_at>=:starts AND starts_at<:ends GROUP BY status")
            .param("org",s.organizationId()).param("branch",s.branchId()).param("starts",Timestamp.from(startsAt)).param("ends",Timestamp.from(endsAt)).query().listOfRows();
    }
    @GetMapping(value="/export",produces="text/csv") public ResponseEntity<String> export(@RequestParam Instant startsAt,@RequestParam Instant endsAt) {
        var result=revenue(startsAt,endsAt);
        String csv="concepto,valor_COP\r\ningresos,"+result.get("gross")+"\r\nreembolsos,"+result.get("refunds")+"\r\nneto,"+result.get("net")+"\r\n";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=ingresos.csv").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(csv);
    }
}
