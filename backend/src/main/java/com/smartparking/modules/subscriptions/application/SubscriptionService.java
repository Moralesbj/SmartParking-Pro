package com.smartparking.modules.subscriptions.application;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.events.Outbox;
import com.smartparking.common.tenancy.Scope;
import com.smartparking.modules.billing.application.BillingService;
import com.smartparking.modules.pricing.application.VehicleRateService;
import com.smartparking.modules.vehicles.domain.VehicleCategory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly contracts. A contract starts PENDING and only becomes ACTIVE when its payment order is fully paid
 * (BillingService). Suspended contracts never grant free parking; expired ones only during the plan's grace days.
 * Each plan belongs to one vehicle category; its price defaults to the category's monthly fee in the branch.
 * Live contracts of one vehicle in a branch cannot overlap (exclusion constraint).
 */
@Service
public class SubscriptionService {
    public static final Duration EXPIRY_NOTICE = Duration.ofDays(3);
    private final JdbcClient db; private final ScopedDatabase data; private final AuditService audit; private final BillingService billing; private final Outbox events;
    private final VehicleRateService rates;

    public SubscriptionService(JdbcClient db, ScopedDatabase data, AuditService audit, BillingService billing, Outbox events, VehicleRateService rates) {
        this.db = db; this.data = data; this.audit = audit; this.billing = billing; this.events = events; this.rates = rates;
    }

    private Map<String, Object> activePlan(Scope s, UUID planId) {
        var plan = data.row(s, "subscription_plans", planId, false);
        if (!Boolean.TRUE.equals(plan.get("active"))) throw ApiException.conflict("Plan inactivo");
        return plan;
    }

    /** Plan price, or the category's monthly fee in this branch when the plan leaves it empty. */
    private BigDecimal price(Scope s, Map<String, Object> plan) {
        return plan.get("price") != null ? (BigDecimal) plan.get("price") : rates.monthlyFee(s, (String) plan.get("vehicle_type"));
    }

    private UUID insert(Scope s, UUID customer, UUID vehicle, Map<String, Object> plan, Instant start, Instant requestedEnd, UUID renewalOf) {
        Instant end = requestedEnd != null ? requestedEnd : start.plus(Duration.ofDays((int) plan.get("duration_days")));
        if (!end.isAfter(start) || end.isAfter(start.plus(Duration.ofDays(366)))) throw new ApiException(422, "El vencimiento debe ser posterior al inicio y máximo a un año");
        if (!end.isAfter(Instant.now())) throw new ApiException(422, "El contrato ya estaría vencido");
        BigDecimal price = price(s, plan);
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO subscriptions(id,organization_id,branch_id,customer_id,vehicle_id,plan_id,starts_at,ends_at,price,renewal_of)
            VALUES(:id,:org,:branch,:customer,:vehicle,:plan,:starts,:ends,:price,:renewal)
            """).param("id", id).param("org", s.organizationId()).param("branch", s.branchId()).param("customer", customer).param("vehicle", vehicle)
            .param("plan", plan.get("id")).param("starts", Timestamp.from(start)).param("ends", Timestamp.from(end)).param("price", price).param("renewal", renewalOf).update();
        billing.createOrder(s, id, true, customer, price);
        return id;
    }

    @Transactional
    public Map<String, Object> create(Scope s, UUID customer, UUID vehicle, UUID planId, Instant start, Instant end) {
        data.lockBranch(s);
        var plan = activePlan(s, planId);
        String type = db.sql("SELECT type FROM vehicles WHERE id=:vehicle AND organization_id=:org AND customer_id=:customer AND active")
            .param("vehicle", vehicle).param("org", s.organizationId()).param("customer", customer).query(String.class).optional()
            .orElseThrow(() -> new ApiException(422, "Vehículo no asociado al cliente"));
        if (!type.equals(plan.get("vehicle_type")))
            throw new ApiException(422, "El plan es para " + VehicleCategory.parse((String) plan.get("vehicle_type")).label() + " y el vehículo es " + VehicleCategory.parse(type).label());
        if (start.isBefore(Instant.now().minus(Duration.ofDays(1)))) throw new ApiException(422, "El inicio no puede ser anterior a ayer");
        UUID id = insert(s, customer, vehicle, plan, start, end, null);
        audit.record(s, "SUBSCRIPTION_CREATED", "subscriptions", id);
        return data.row(s, "subscriptions", id, false);
    }

    /** Next period starts when the current one ends (or now if already expired). One renewal per contract. */
    @Transactional
    public Map<String, Object> renew(Scope s, UUID id) {
        data.lockBranch(s);
        var current = data.row(s, "subscriptions", id, true);
        String status = (String) current.get("status");
        if (!status.equals("ACTIVE") && !status.equals("EXPIRED")) throw ApiException.conflict("Solo se renuevan contratos activos o vencidos");
        if (db.sql("SELECT count(*) FROM subscriptions WHERE renewal_of=:id").param("id", id).query(Long.class).single() > 0)
            throw ApiException.conflict("El contrato ya tiene una renovación");
        var plan = activePlan(s, (UUID) current.get("plan_id"));
        Instant end = ((Timestamp) current.get("ends_at")).toInstant(), now = Instant.now();
        UUID next = insert(s, (UUID) current.get("customer_id"), (UUID) current.get("vehicle_id"), plan, end.isAfter(now) ? end : now, null, id);
        audit.record(s, "SUBSCRIPTION_RENEWED", "subscriptions", next);
        return data.row(s, "subscriptions", next, false);
    }

    @Transactional
    public Map<String, Object> suspend(Scope s, UUID id) {
        data.lockBranch(s);
        var row = data.row(s, "subscriptions", id, true);
        if (!row.get("status").equals("ACTIVE")) throw ApiException.conflict("Solo se suspenden contratos activos");
        db.sql("UPDATE subscriptions SET status='SUSPENDED',updated_at=now() WHERE id=:id").param("id", id).update();
        audit.record(s, "SUBSCRIPTION_SUSPENDED", "subscriptions", id);
        return data.row(s, "subscriptions", id, false);
    }

    /** Reactivation restores benefits only within the paid period; past the end date the contract expires. */
    @Transactional
    public Map<String, Object> reactivate(Scope s, UUID id) {
        data.lockBranch(s);
        var row = data.row(s, "subscriptions", id, true);
        if (!row.get("status").equals("SUSPENDED")) throw ApiException.conflict("El contrato no está suspendido");
        db.sql("UPDATE subscriptions SET status=CASE WHEN ends_at>now() THEN 'ACTIVE' ELSE 'EXPIRED' END,updated_at=now() WHERE id=:id").param("id", id).update();
        audit.record(s, "SUBSCRIPTION_REACTIVATED", "subscriptions", id);
        return data.row(s, "subscriptions", id, false);
    }

    /** Branches where a plan's contracts apply: the plan's own branch plus the authorized ones. */
    public List<Map<String, Object>> planBranches(Scope s, UUID planId) {
        var plan = data.row(s, "subscription_plans", planId, false);
        return db.sql("""
            SELECT b.id,b.code,b.name,(b.id=:own) own FROM branches b
            WHERE b.organization_id=:org AND (b.id=:own OR b.id IN (SELECT branch_id FROM subscription_plan_branches WHERE plan_id=:plan))
            ORDER BY (b.id=:own) DESC,b.name
            """).param("org", s.organizationId()).param("own", plan.get("branch_id")).param("plan", planId).query().listOfRows();
    }

    /** Replaces the additional branches. The administrator must administer each of them. */
    @Transactional
    public List<Map<String, Object>> authorizeBranches(Scope s, UUID planId, Set<UUID> branches) {
        data.lockBranch(s);
        var plan = data.row(s, "subscription_plans", planId, true);
        for (UUID branch : branches) {
            if (branch.equals(plan.get("branch_id"))) continue;
            long allowed = db.sql("""
                SELECT count(*) FROM branches b WHERE b.id=:branch AND b.organization_id=:org AND b.active AND EXISTS(
                  SELECT 1 FROM user_memberships m WHERE m.user_id=:actor AND m.organization_id=:org AND m.active
                    AND m.role IN ('ADMIN','SUPERADMIN') AND (m.branch_id IS NULL OR m.branch_id=:branch))
                """).param("branch", branch).param("org", s.organizationId()).param("actor", s.actorId()).query(Long.class).single();
            if (allowed != 1) throw new ApiException(403, "No administra una de las sedes indicadas");
        }
        db.sql("DELETE FROM subscription_plan_branches WHERE plan_id=:plan").param("plan", planId).update();
        for (UUID branch : branches) {
            if (branch.equals(plan.get("branch_id"))) continue;
            db.sql("INSERT INTO subscription_plan_branches(organization_id,plan_branch_id,plan_id,branch_id) VALUES(:org,:own,:plan,:branch)")
                .param("org", s.organizationId()).param("own", plan.get("branch_id")).param("plan", planId).param("branch", branch).update();
        }
        audit.record(s, "SUBSCRIPTION_PLAN_BRANCHES", "subscription_plans", planId);
        return planBranches(s, planId);
    }

    /** Idempotent job step: expires overdue contracts and emits one SubscriptionExpiring event per contract. */
    @Transactional
    public int processLifecycle() {
        db.sql("UPDATE subscriptions SET status='EXPIRED',updated_at=now() WHERE status='ACTIVE' AND ends_at<=now()").update();
        var expiring = db.sql("""
            UPDATE subscriptions s SET expiry_notified_at=now()
            WHERE s.status='ACTIVE' AND s.expiry_notified_at IS NULL AND s.ends_at<=now()+CAST(:notice AS interval)
              AND NOT EXISTS(SELECT 1 FROM subscriptions r WHERE r.renewal_of=s.id)
            RETURNING s.id,s.organization_id,s.branch_id
            """).param("notice", EXPIRY_NOTICE.toDays() + " days").query().listOfRows();
        for (var e : expiring) events.emitSystem((UUID) e.get("organization_id"), (UUID) e.get("branch_id"), "SubscriptionExpiring", (UUID) e.get("id"));
        return expiring.size();
    }
}
