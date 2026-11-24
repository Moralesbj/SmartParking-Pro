package com.smartparking.modules.billing.application;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.events.Outbox;
import com.smartparking.common.tenancy.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingService {
    private final JdbcClient db; private final ScopedDatabase data; private final AuditService audit; private final Outbox events;
    public BillingService(JdbcClient db,ScopedDatabase data,AuditService audit,Outbox events) { this.db=db;this.data=data;this.audit=audit;this.events=events; }
    public Map<String,Object> createOrder(Scope s,UUID source,boolean subscription,UUID customer,BigDecimal amount) { return createOrder(s,source,subscription,customer,amount,null); }
    /**
     * Creates or re-prices the single order of a stay or contract. {@code pricedAt} is the instant the amount was computed for.
     * A pending order follows the latest liquidation. A paid order is reopened only when the stay has grown (more time
     * after paying); a lower amount never reduces what was already paid, that requires a supervised refund.
     */
    public Map<String,Object> createOrder(Scope s,UUID source,boolean subscription,UUID customer,BigDecimal amount,java.time.Instant pricedAt) {
        String sourceColumn=subscription?"subscription_id":"session_id";
        var existing=db.sql("SELECT * FROM payment_orders WHERE organization_id=:org AND branch_id=:branch AND "+sourceColumn+"=:source FOR UPDATE")
            .param("org",s.organizationId()).param("branch",s.branchId()).param("source",source).query().listOfRows().stream().findFirst();
        java.sql.Timestamp priced=pricedAt==null?null:java.sql.Timestamp.from(pricedAt);
        if(existing.isPresent()) {
            var order=existing.get(); String status=(String)order.get("status"); BigDecimal current=(BigDecimal)order.get("amount");
            if(status.equals("REFUNDED")) return order;
            if(status.equals("PAID") && amount.compareTo(current)<=0) return order;
            BigDecimal paid=db.sql("SELECT coalesce(sum(amount),0) FROM payments WHERE order_id=:id").param("id",order.get("id")).query(BigDecimal.class).single();
            if(amount.compareTo(paid)<0) throw ApiException.conflict("El nuevo valor es menor que lo ya pagado; requiere ajuste supervisado");
            if(amount.compareTo(current)==0 && status.equals("PENDING")) {
                db.sql("UPDATE payment_orders SET priced_at=:priced,updated_at=now() WHERE id=:id").param("priced",priced).param("id",order.get("id")).update();
                return data.row(s,"payment_orders",(UUID)order.get("id"),false);
            }
            db.sql("UPDATE payment_orders SET amount=:amount,status=:status,priced_at=:priced,updated_at=now() WHERE id=:id")
                .param("amount",amount).param("status",amount.compareTo(paid)==0?"PAID":"PENDING").param("priced",priced).param("id",order.get("id")).update();
            audit.record(s,"ORDER_REPRICED","payment_orders",(UUID)order.get("id"));
            return data.row(s,"payment_orders",(UUID)order.get("id"),false);
        }
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO payment_orders(id,organization_id,branch_id,"+sourceColumn+",customer_id,amount,status,priced_at) VALUES(:id,:org,:branch,:source,:customer,:amount,:status,:priced)")
            .param("id",id).param("org",s.organizationId()).param("branch",s.branchId()).param("source",source).param("customer",customer).param("amount",amount).param("status",amount.signum()==0?"PAID":"PENDING").param("priced",priced).update();
        audit.record(s,"ORDER_CREATED","payment_orders",id); return data.row(s,"payment_orders",id,false);
    }
    @Transactional public Map<String,Object> capture(Scope s,UUID orderId,UUID cashId,String method,BigDecimal amount,String reference,UUID key) {
        // Shared branch lock defines lock order across financial operations and check-out.
        data.lockBranch(s);
        var duplicate=db.sql("SELECT * FROM payments WHERE organization_id=:org AND idempotency_key=:key").param("org",s.organizationId()).param("key",key).query().listOfRows().stream().findFirst();
        if(duplicate.isPresent()) {
            var p=duplicate.get();
            if(!p.get("order_id").equals(orderId) || !p.get("cash_session_id").equals(cashId) || ((BigDecimal)p.get("amount")).compareTo(amount)!=0 || !p.get("method").equals(method)) throw ApiException.conflict("La clave idempotente se usó con otros datos");
            if(!p.get("branch_id").equals(s.branchId())) throw new ApiException(403,"Sede incorrecta");
            return p;
        }
        if(!Set.of("CASH","TRANSFER","CARD").contains(method) || amount==null || amount.signum()<=0 || amount.scale()>2) throw new ApiException(422,"Pago inválido");
        if(!method.equals("CASH") && (reference==null || reference.isBlank())) throw new ApiException(422,"Se requiere referencia del comprobante externo");
        var order=data.row(s,"payment_orders",orderId,true); var cash=data.row(s,"cash_sessions",cashId,true);
        if(!cash.get("status").equals("OPEN") || !cash.get("operator_id").equals(s.actorId())) throw ApiException.conflict("Abra su propia caja antes de cobrar");
        if(!order.get("status").equals("PENDING")) throw ApiException.conflict("La orden no admite pagos");
        BigDecimal paid=db.sql("SELECT coalesce(sum(amount),0) FROM payments WHERE order_id=:order").param("order",orderId).query(BigDecimal.class).single();
        BigDecimal total=(BigDecimal)order.get("amount");
        if(paid.add(amount).compareTo(total)>0) throw ApiException.conflict("El pago supera el saldo");
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO payments(id,organization_id,branch_id,order_id,cash_session_id,amount,method,reference,idempotency_key) VALUES(:id,:org,:branch,:order,:cash,:amount,:method,:ref,:key)")
            .param("id",id).param("org",s.organizationId()).param("branch",s.branchId()).param("order",orderId).param("cash",cashId).param("amount",amount).param("method",method).param("ref",reference==null?"":reference).param("key",key).update();
        if(paid.add(amount).compareTo(total)==0) {
            db.sql("UPDATE payment_orders SET status='PAID',updated_at=now() WHERE id=:id").param("id",orderId).update();
            if(order.get("subscription_id")!=null) db.sql("UPDATE subscriptions SET status=CASE WHEN ends_at>now() THEN 'ACTIVE' ELSE 'EXPIRED' END,updated_at=now() WHERE id=:id AND status='PENDING'").param("id",order.get("subscription_id")).update();
        }
        audit.record(s,"PAYMENT_CAPTURED","payments",id);events.emit(s,"PaymentCaptured",id);
        return data.row(s,"payments",id,false);
    }
    @Transactional public Map<String,Object> refund(Scope s,UUID paymentId,String reason,UUID key) {
        if(!s.supervisor()) throw new ApiException(403,"Reembolso requiere supervisión");
        if(reason==null || reason.isBlank() || reason.length()>400) throw new ApiException(422,"Indique motivo del reembolso");
        data.lockBranch(s);
        var prior=db.sql("SELECT id,payment_id FROM refunds WHERE organization_id=:org AND idempotency_key=:key").param("org",s.organizationId()).param("key",key).query().listOfRows().stream().findFirst();
        if(prior.isPresent()) { if(!prior.get().get("payment_id").equals(paymentId)) throw ApiException.conflict("Clave ya utilizada"); return prior.get(); }
        var payment=data.row(s,"payments",paymentId,true);
        var cash=data.row(s,"cash_sessions",(UUID)payment.get("cash_session_id"),true);
        if(!cash.get("status").equals("OPEN")) throw ApiException.conflict("No se reversa sobre caja cerrada");
        if(db.sql("SELECT count(*) FROM refunds WHERE payment_id=:id").param("id",paymentId).query(Long.class).single()>0) throw ApiException.conflict("Pago ya reembolsado");
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO refunds(id,organization_id,branch_id,payment_id,amount,reason,approved_by,idempotency_key) VALUES(:id,:org,:branch,:payment,:amount,:reason,:actor,:key)")
            .param("id",id).param("org",s.organizationId()).param("branch",s.branchId()).param("payment",paymentId).param("amount",payment.get("amount")).param("reason",reason).param("actor",s.actorId()).param("key",key).update();
        db.sql("UPDATE payment_orders SET status='REFUNDED',updated_at=now() WHERE id=:id").param("id",payment.get("order_id")).update();
        audit.record(s,"PAYMENT_REFUNDED","refunds",id); return Map.of("id",id,"status","REFUNDED");
    }
}
