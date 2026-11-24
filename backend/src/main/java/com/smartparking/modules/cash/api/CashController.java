package com.smartparking.modules.cash.api;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.tenancy.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/cash-sessions")
public class CashController {
    private final JdbcClient db;private final ScopedDatabase data;private final ScopeService scopes;private final AuditService audit;
    public CashController(JdbcClient db,ScopedDatabase data,ScopeService scopes,AuditService audit) { this.db=db;this.data=data;this.scopes=scopes;this.audit=audit; }
    public record Open(@NotNull @DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal openingBalance) {}
    public record Close(@NotNull @DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal countedBalance) {}
    public record Movement(@NotNull @Digits(integer=12,fraction=2) BigDecimal amount,@NotBlank @Size(max=400) String reason) {}
    @GetMapping public List<Map<String,Object>> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return data.list(scopes.staff(),"cash_sessions",page,size); }
    @PostMapping("/open") @Transactional public Map<String,Object> open(@Valid @RequestBody Open b) {
        var s=scopes.staff();data.lockBranch(s);UUID id=UUID.randomUUID();
        db.sql("INSERT INTO cash_sessions(id,organization_id,branch_id,operator_id,opening_balance) VALUES(:id,:org,:branch,:actor,:balance)")
            .param("id",id).param("org",s.organizationId()).param("branch",s.branchId()).param("actor",s.actorId()).param("balance",b.openingBalance()).update();
        audit.record(s,"CASH_OPENED","cash_sessions",id);return data.row(s,"cash_sessions",id,false);
    }
    public BigDecimal expected(Map<String,Object> cash,UUID id) {
        return ((BigDecimal)cash.get("opening_balance")).add(db.sql("SELECT coalesce(sum(amount),0) FROM payments WHERE cash_session_id=:id AND method='CASH'").param("id",id).query(BigDecimal.class).single())
            .subtract(db.sql("SELECT coalesce(sum(r.amount),0) FROM refunds r JOIN payments p ON p.id=r.payment_id WHERE p.cash_session_id=:id AND p.method='CASH'").param("id",id).query(BigDecimal.class).single())
            .add(db.sql("SELECT coalesce(sum(amount),0) FROM cash_movements WHERE cash_session_id=:id").param("id",id).query(BigDecimal.class).single());
    }
    @GetMapping("/{id}/balance") public Map<String,Object> balance(@PathVariable UUID id) {
        var s=scopes.staff();var cash=data.row(s,"cash_sessions",id,false);
        if(!s.supervisor() && !cash.get("operator_id").equals(s.actorId())) throw new ApiException(403,"Caja de otro operador");
        return Map.of("cashExpected",expected(cash,id),"byMethod",db.sql("SELECT method,sum(amount) total FROM payments WHERE cash_session_id=:id GROUP BY method").param("id",id).query().listOfRows());
    }
    @PostMapping("/{id}/close") @Transactional public Map<String,Object> close(@PathVariable UUID id,@Valid @RequestBody Close b) {
        var s=scopes.staff();data.lockBranch(s);var cash=data.row(s,"cash_sessions",id,true);
        if(!s.supervisor() && !cash.get("operator_id").equals(s.actorId())) throw new ApiException(403,"Caja de otro operador");
        if(!cash.get("status").equals("OPEN")) throw ApiException.conflict("Caja ya cerrada");
        BigDecimal expected=expected(cash,id);
        db.sql("UPDATE cash_sessions SET status='CLOSED',closed_at=now(),expected_balance=:expected,counted_balance=:counted,updated_at=now() WHERE id=:id")
            .param("expected",expected).param("counted",b.countedBalance()).param("id",id).update();
        if(expected.compareTo(b.countedBalance())!=0) db.sql("INSERT INTO anomaly_alerts(id,organization_id,branch_id,type,detail,reference_id) VALUES(:id,:org,:branch,'CASH_DIFFERENCE','Diferencia detectada en arqueo de caja',:ref)")
            .param("id",UUID.randomUUID()).param("org",s.organizationId()).param("branch",s.branchId()).param("ref",id).update();
        audit.record(s,"CASH_CLOSED","cash_sessions",id);return data.row(s,"cash_sessions",id,false);
    }
    @PostMapping("/{id}/movements") @Transactional public Map<String,UUID> movement(@PathVariable UUID id,@Valid @RequestBody Movement b) {
        var s=scopes.supervisor();data.lockBranch(s);var cash=data.row(s,"cash_sessions",id,true);
        if(!cash.get("status").equals("OPEN") || b.amount().signum()==0) throw ApiException.conflict("Caja cerrada o importe inválido");
        UUID ref=UUID.randomUUID();
        db.sql("INSERT INTO cash_movements(id,organization_id,branch_id,cash_session_id,amount,reason,approved_by) VALUES(:id,:org,:branch,:cash,:amount,:reason,:actor)")
            .param("id",ref).param("org",s.organizationId()).param("branch",s.branchId()).param("cash",id).param("amount",b.amount()).param("reason",b.reason()).param("actor",s.actorId()).update();
        audit.record(s,"CASH_MOVEMENT","cash_movements",ref);return Map.of("id",ref);
    }
}
