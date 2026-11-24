package com.smartparking.modules.parking.application;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.events.Outbox;
import com.smartparking.common.tenancy.Scope;
import com.smartparking.modules.billing.application.BillingService;
import com.smartparking.modules.pricing.application.ParkingRateService;
import com.smartparking.modules.pricing.application.VehicleRateService;
import com.smartparking.modules.pricing.domain.ParkingRateCalculationDTO;
import com.smartparking.modules.pricing.domain.ParkingRateRequest;
import com.smartparking.modules.subscriptions.application.SubscriptionCoverage;
import com.smartparking.modules.vehicles.domain.VehicleCategory;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entries, liquidations and exits. Every amount comes from ParkingRateService applied to the rate frozen at entry;
 * operators can trigger a liquidation but never set its value (RN-010, RN-014).
 */
@Service
public class ParkingService {
    /** Content of the ticket's QR code: prefix + stay id (unguessable, readable only by branch staff). */
    public static final String QR_PREFIX = "SPK1:";
    private final JdbcClient db; private final ScopedDatabase data; private final AuditService audit; private final Outbox events;
    private final BillingService billing; private final VehicleRateService rates; private final ParkingRateService engine; private final SubscriptionCoverage coverage;
    private final SecureRandom random = new SecureRandom();

    public ParkingService(JdbcClient db, ScopedDatabase data, AuditService audit, Outbox events, BillingService billing,
                          VehicleRateService rates, ParkingRateService engine, SubscriptionCoverage coverage) {
        this.db = db; this.data = data; this.audit = audit; this.events = events; this.billing = billing;
        this.rates = rates; this.engine = engine; this.coverage = coverage;
    }

    /**
     * Admits a vehicle. The space is optional: capacity is enforced per category (open stays + reservations in force
     * must stay below the configured capacity). The branch lock serializes concurrent entries.
     */
    @Transactional
    public Map<String, Object> checkIn(Scope s, UUID vehicle, UUID space, UUID key, UUID reservation) {
        data.lockBranch(s);
        var prior = db.sql("SELECT * FROM parking_sessions WHERE organization_id=:org AND idempotency_key=:key").param("org", s.organizationId()).param("key", key).query().listOfRows().stream().findFirst();
        if (prior.isPresent()) {
            var p = prior.get();
            if (!p.get("vehicle_id").equals(vehicle) || !Objects.equals(p.get("space_id"), space) || !p.get("branch_id").equals(s.branchId())) throw ApiException.conflict("Clave idempotente con otros datos");
            return p;
        }
        var v = db.sql("SELECT * FROM vehicles WHERE id=:id AND organization_id=:org AND active").param("id", vehicle).param("org", s.organizationId())
            .query().listOfRows().stream().findFirst().orElseThrow(ApiException::notFound);
        String type = (String) v.get("type");
        var category = VehicleCategory.parse(type);
        var rate = rates.forEntry(s, type);
        int capacity = (int) rate.get("capacity");
        long used = occupied(s, type) + reservedNow(s, type, reservation);
        if (capacity == 0) throw ApiException.conflict("Configure la capacidad de " + category.label() + " en Tarifas por vehículo");
        if (used >= capacity) throw ApiException.conflict("Sin cupo disponible para " + category.label() + " (capacidad " + capacity + ")");
        if (space != null) {
            var sp = data.row(s, "parking_spaces", space, true);
            if (!Boolean.TRUE.equals(sp.get("active")) || !sp.get("status").equals("AVAILABLE") || !sp.get("type").equals(type))
                throw ApiException.conflict("Cupo no disponible para este vehículo");
            // Without a promised exit, do not allocate a space with any future confirmed reservation.
            long reserved = db.sql("SELECT count(*) FROM reservations WHERE space_id=:space AND status IN('CONFIRMED','CHECKED_IN') AND ends_at>now() AND (CAST(:reservation AS uuid) IS NULL OR id<>:reservation)")
                .param("space", space).param("reservation", reservation).query(Long.class).single();
            if (reserved > 0) throw ApiException.conflict("Cupo reservado; seleccione otro cupo");
        }
        Instant now = db.sql("SELECT now()").query(Timestamp.class).single().toInstant();
        UUID subscription = coverage.validAt(s, vehicle, type, now).orElse(null);
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO parking_sessions(id,organization_id,branch_id,vehicle_id,space_id,entry_operator_id,vehicle_type,minute_rate,full_rate,block_minutes,
              grace_minutes,rate_id,rate_version,ticket_code,subscription_id,pricing_model,idempotency_key)
            VALUES(:id,:org,:branch,:vehicle,:space,:actor,:type,:minute,:full,:block,:grace,:rate,:version,:code,:subscription,'MINUTE_BLOCKS',:key)
            """).param("id", id).param("org", s.organizationId()).param("branch", s.branchId()).param("vehicle", vehicle).param("space", space).param("actor", s.actorId())
            .param("type", type).param("minute", rate.get("minute_rate")).param("full", rate.get("full_rate")).param("block", rate.get("block_minutes"))
            .param("grace", rate.get("grace_minutes")).param("rate", rate.get("id")).param("version", rate.get("version")).param("code", ticketCode(s))
            .param("subscription", subscription).param("key", key).update();
        if (Boolean.TRUE.equals(v.get("watchlisted")))
            db.sql("INSERT INTO anomaly_alerts(id,organization_id,branch_id,type,detail,reference_id) VALUES(:id,:org,:branch,'WATCHLIST','Ingreso de vehículo en observación',:ref)")
                .param("id", UUID.randomUUID()).param("org", s.organizationId()).param("branch", s.branchId()).param("ref", id).update();
        audit.record(s, "CHECK_IN", "parking_sessions", id);
        events.emit(s, "ParkingSessionOpened", id);
        var row = new LinkedHashMap<>(data.row(s, "parking_sessions", id, false));
        row.put("qr_payload", QR_PREFIX + id);
        row.put("monthly_customer", subscription != null);
        return row;
    }

    long occupied(Scope s, String type) {
        return db.sql("SELECT count(*) FROM parking_sessions WHERE branch_id=:branch AND status='OPEN' AND vehicle_type=:type")
            .param("branch", s.branchId()).param("type", type).query(Long.class).single();
    }

    /** Reservations of this category in force now and not yet used (they hold capacity for their customer). */
    long reservedNow(Scope s, String type, UUID except) {
        return db.sql("""
            SELECT count(*) FROM reservations r JOIN vehicles v ON v.id=r.vehicle_id
            WHERE r.organization_id=:org AND r.branch_id=:branch AND r.status='CONFIRMED' AND r.starts_at<=now() AND r.ends_at>now() AND v.type=:type
              AND (CAST(:except AS uuid) IS NULL OR r.id<>:except)
            """).param("org", s.organizationId()).param("branch", s.branchId()).param("type", type).param("except", except).query(Long.class).single();
    }

    /** Random six-digit code, unique among the branch's open stays (the branch lock is held by the caller). */
    private String ticketCode(Scope s) {
        for (int attempt = 0; attempt < 50; attempt++) {
            String code = String.format("%06d", random.nextInt(1_000_000));
            long used = db.sql("SELECT count(*) FROM parking_sessions WHERE branch_id=:branch AND status='OPEN' AND ticket_code=:code")
                .param("branch", s.branchId()).param("code", code).query(Long.class).single();
            if (used == 0) return code;
        }
        throw ApiException.conflict("No fue posible asignar un código de tiquete; intente de nuevo");
    }

    // ------------------------------------------------------------------ liquidation

    /** Live liquidation of a stay (read-only). A stay with a paid order shows the liquidation it was paid for. */
    @Transactional(readOnly = true)
    public Map<String, Object> liquidation(Scope s, UUID id) {
        return view(s, data.row(s, "parking_sessions", id, false));
    }

    /** Finds a stay by plate, six-digit ticket code or QR content and returns its liquidation. */
    @Transactional(readOnly = true)
    public Map<String, Object> lookup(Scope s, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || q.length() > 60) throw new ApiException(422, "Ingrese una placa, el código de seis dígitos o lea el código QR");
        Optional<UUID> id;
        if (q.matches("\\d{6}")) {
            id = db.sql("SELECT id FROM parking_sessions WHERE organization_id=:org AND branch_id=:branch AND status='OPEN' AND ticket_code=:code")
                .param("org", s.organizationId()).param("branch", s.branchId()).param("code", q).query(UUID.class).optional();
        } else if (q.toUpperCase(Locale.ROOT).startsWith(QR_PREFIX)) {
            UUID parsed;
            try { parsed = UUID.fromString(q.substring(QR_PREFIX.length()).trim()); } catch (IllegalArgumentException e) { throw new ApiException(422, "Código QR no reconocido"); }
            id = db.sql("SELECT id FROM parking_sessions WHERE organization_id=:org AND branch_id=:branch AND id=:id")
                .param("org", s.organizationId()).param("branch", s.branchId()).param("id", parsed).query(UUID.class).optional();
        } else {
            String plate = q.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
            id = db.sql("""
                SELECT p.id FROM parking_sessions p JOIN vehicles v ON v.id=p.vehicle_id
                WHERE p.organization_id=:org AND p.branch_id=:branch AND v.plate=:plate ORDER BY (p.status='OPEN') DESC, p.entry_at DESC LIMIT 1
                """).param("org", s.organizationId()).param("branch", s.branchId()).param("plate", plate).query(UUID.class).optional();
        }
        return liquidation(s, id.orElseThrow(() -> new ApiException(404, "No se encontró un tiquete para «" + q + "» en esta sede")));
    }

    /** Liquidates now: computes the amount in real time and creates or re-prices the stay's payment order. */
    @Transactional
    public Map<String, Object> quote(Scope s, UUID id) {
        data.lockBranch(s);
        var session = data.row(s, "parking_sessions", id, true);
        if (!session.get("status").equals("OPEN")) throw ApiException.conflict("Estancia cerrada");
        var live = calculate(s, session, now(), payments(order(s, id)));
        var customer = db.sql("SELECT customer_id FROM vehicles WHERE id=:id AND organization_id=:org").param("id", session.get("vehicle_id")).param("org", s.organizationId()).query().singleRow().get("customer_id");
        billing.createOrder(s, id, false, (UUID) customer, live.totalAmount(), live.settledAt());
        return view(s, data.row(s, "parking_sessions", id, false));
    }

    /** Supervised discount: a fixed amount with a reason, never above the pending balance (RN-011). */
    @Transactional
    public Map<String, Object> discount(Scope s, UUID id, BigDecimal amount, String reason, UUID key) {
        if (!s.supervisor()) throw new ApiException(403, "Los descuentos requieren supervisión");
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) throw new ApiException(422, "Valor de descuento inválido");
        if (reason == null || reason.isBlank() || reason.length() > 400) throw new ApiException(422, "Indique el motivo del descuento");
        data.lockBranch(s);
        var prior = db.sql("SELECT session_id,amount FROM session_discounts WHERE organization_id=:org AND idempotency_key=:key").param("org", s.organizationId()).param("key", key).query().listOfRows().stream().findFirst();
        if (prior.isPresent()) {
            if (!prior.get().get("session_id").equals(id) || ((BigDecimal) prior.get().get("amount")).compareTo(amount) != 0) throw ApiException.conflict("Clave idempotente con otros datos");
            return liquidation(s, id);
        }
        var session = data.row(s, "parking_sessions", id, true);
        if (!session.get("status").equals("OPEN")) throw ApiException.conflict("Estancia cerrada");
        var live = calculate(s, session, now(), payments(order(s, id)));
        if (amount.compareTo(live.balanceDue()) > 0) throw ApiException.conflict("El descuento supera el saldo pendiente (" + live.balanceDue().toPlainString() + ")");
        UUID discountId = UUID.randomUUID();
        db.sql("INSERT INTO session_discounts(id,organization_id,branch_id,session_id,amount,reason,approved_by,idempotency_key) VALUES(:id,:org,:branch,:session,:amount,:reason,:actor,:key)")
            .param("id", discountId).param("org", s.organizationId()).param("branch", s.branchId()).param("session", id).param("amount", amount)
            .param("reason", reason.trim()).param("actor", s.actorId()).param("key", key).update();
        audit.record(s, "DISCOUNT_GRANTED", "session_discounts", discountId);
        if (order(s, id) != null) return quote(s, id);
        return liquidation(s, id);
    }

    @Transactional
    public Map<String, Object> checkOut(Scope s, UUID id) {
        data.lockBranch(s);
        var session = data.row(s, "parking_sessions", id, true);
        if (session.get("status").equals("CLOSED")) return session;
        var order = db.sql("SELECT * FROM payment_orders WHERE session_id=:id AND organization_id=:org AND branch_id=:branch FOR UPDATE")
            .param("id", id).param("org", s.organizationId()).param("branch", s.branchId()).query().listOfRows().stream().findFirst()
            .orElseThrow(() -> ApiException.conflict("Liquide la estancia y registre el pago"));
        if (!order.get("status").equals("PAID")) throw ApiException.conflict("La estancia tiene saldo pendiente");
        db.sql("UPDATE parking_sessions SET status='CLOSED',exit_at=now(),exit_operator_id=:actor,final_amount=:amount,updated_at=now() WHERE id=:id")
            .param("actor", s.actorId()).param("amount", order.get("amount")).param("id", id).update();
        db.sql("UPDATE reservations SET status='COMPLETED',updated_at=now() WHERE session_id=:id AND status='CHECKED_IN'").param("id", id).update();
        audit.record(s, "CHECK_OUT", "parking_sessions", id);
        events.emit(s, "ParkingSessionClosed", id);
        return data.row(s, "parking_sessions", id, false);
    }

    private Instant now() { return db.sql("SELECT now()").query(Timestamp.class).single().toInstant(); }

    private Map<String, Object> order(Scope s, UUID session) {
        return db.sql("SELECT * FROM payment_orders WHERE organization_id=:org AND branch_id=:branch AND session_id=:id")
            .param("org", s.organizationId()).param("branch", s.branchId()).param("id", session).query().listOfRows().stream().findFirst().orElse(null);
    }

    /** Net amount paid on the order: payments minus refunds. */
    private BigDecimal payments(Map<String, Object> order) {
        if (order == null) return BigDecimal.ZERO;
        return db.sql("SELECT coalesce(sum(p.amount),0)-coalesce(sum(r.amount),0) FROM payments p LEFT JOIN refunds r ON r.payment_id=p.id WHERE p.order_id=:id")
            .param("id", order.get("id")).query(BigDecimal.class).single();
    }

    private ParkingRateCalculationDTO calculate(Scope s, Map<String, Object> session, Instant at, BigDecimal paid) {
        UUID id = (UUID) session.get("id"), vehicle = (UUID) session.get("vehicle_id");
        String type = (String) session.get("vehicle_type");
        Instant entry = ((Timestamp) session.get("entry_at")).toInstant();
        if (at.isBefore(entry)) at = entry;
        BigDecimal discounts = db.sql("SELECT coalesce(sum(amount),0) FROM session_discounts WHERE session_id=:id").param("id", id).query(BigDecimal.class).single();
        var covered = coverage.periods(s, vehicle, type, entry, at, data.zone(s));
        return engine.calculate(new ParkingRateRequest(entry, at, type, (BigDecimal) session.get("minute_rate"), (BigDecimal) session.get("full_rate"),
            (int) session.get("block_minutes"), (int) session.get("grace_minutes"), discounts, paid, covered));
    }

    /**
     * Everything the operator sees when consulting a ticket: stay, vehicle, frozen rate, block-by-block calculation,
     * discounts, payments, balance, monthly-plan status and ticket status.
     */
    private Map<String, Object> view(Scope s, Map<String, Object> session) {
        UUID id = (UUID) session.get("id");
        var vehicle = db.sql("SELECT plate,type,brand,customer_id FROM vehicles WHERE id=:id AND organization_id=:org").param("id", session.get("vehicle_id")).param("org", s.organizationId()).query().singleRow();
        String type = (String) session.get("vehicle_type");
        var order = order(s, id);
        boolean open = session.get("status").equals("OPEN"), paid = order != null && order.get("status").equals("PAID");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", id);
        out.put("ticketNumber", session.get("ticket_number"));
        out.put("ticketCode", session.get("ticket_code"));
        out.put("qrPayload", QR_PREFIX + id);
        out.put("plate", vehicle.get("plate"));
        out.put("vehicleType", type);
        out.put("vehicleTypeLabel", VehicleCategory.parse(type).label());
        out.put("entryAt", session.get("entry_at"));
        out.put("exitAt", session.get("exit_at"));
        out.put("status", session.get("status"));
        out.put("ticketStatus", !open ? "CLOSED" : paid ? "PAID" : "PENDING_PAYMENT");
        out.put("pricingModel", session.get("pricing_model"));
        out.put("rateVersion", session.get("rate_version"));
        if ("HOURLY_LEGACY".equals(session.get("pricing_model"))) {
            // Stays closed before the per-minute model keep their final amount; they are not re-priced.
            out.put("finalAmount", session.get("final_amount"));
            out.put("calculation", null);
        } else {
            // Paid or closed stays show the liquidation that was paid; open unpaid stays are priced at this instant.
            Instant at = (paid || !open) && order != null && order.get("priced_at") != null ? ((Timestamp) order.get("priced_at")).toInstant()
                : !open && session.get("exit_at") != null ? ((Timestamp) session.get("exit_at")).toInstant() : now();
            var calc = calculate(s, session, at, payments(order));
            out.put("calculation", calc);
            out.put("settled", paid || !open);
            out.put("finalAmount", session.get("final_amount"));
        }
        Instant statusAt = open ? now() : ((Timestamp) session.get("exit_at")).toInstant();
        out.put("monthlyCustomer", session.get("subscription_id") != null);
        out.put("subscription", coverage.status(s, (UUID) session.get("vehicle_id"), type, statusAt));
        out.put("discounts", db.sql("SELECT d.id,d.amount,d.reason,d.created_at,u.full_name approved_by_name FROM session_discounts d JOIN users u ON u.id=d.approved_by WHERE d.session_id=:id ORDER BY d.created_at")
            .param("id", id).query().listOfRows());
        out.put("order", order == null ? null : Map.of("id", order.get("id"), "amount", order.get("amount"), "status", order.get("status"),
            "pricedAt", order.get("priced_at") == null ? "" : order.get("priced_at")));
        return out;
    }
}
