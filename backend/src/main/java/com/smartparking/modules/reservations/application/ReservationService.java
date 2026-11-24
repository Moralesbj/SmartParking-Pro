package com.smartparking.modules.reservations.application;

import com.smartparking.common.api.ScopedDatabase;
import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.events.Outbox;
import com.smartparking.common.tenancy.Scope;
import com.smartparking.modules.parking.application.ParkingService;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
    private final JdbcClient db; private final ScopedDatabase data; private final AuditService audit; private final Outbox events; private final ParkingService parking;
    public ReservationService(JdbcClient db,ScopedDatabase data,AuditService audit,Outbox events,ParkingService parking) { this.db=db;this.data=data;this.audit=audit;this.events=events;this.parking=parking; }
    public List<Map<String,Object>> availability(Scope s,String type,Instant start,Instant end) {
        validateInterval(start,end);
        return db.sql("""
            SELECT p.id,p.code,p.type FROM parking_spaces p
            WHERE p.organization_id=:org AND p.branch_id=:branch AND p.active AND p.status='AVAILABLE' AND p.type=:type
            AND NOT EXISTS(SELECT 1 FROM parking_sessions x WHERE x.space_id=p.id AND x.status='OPEN')
            AND NOT EXISTS(SELECT 1 FROM reservations r WHERE r.space_id=p.id AND r.status IN('CONFIRMED','CHECKED_IN')
              AND r.starts_at<:ends AND r.ends_at>:starts) ORDER BY p.code LIMIT 100
            """).param("org",s.organizationId()).param("branch",s.branchId()).param("type",type).param("starts",Timestamp.from(start)).param("ends",Timestamp.from(end)).query().listOfRows();
    }
    private void validateInterval(Instant start,Instant end) {
        if(start==null || end==null || !end.isAfter(start) || start.isBefore(Instant.now().minusSeconds(60)) || end.isAfter(start.plusSeconds(86400*30L))) throw new ApiException(422,"Intervalo inválido; máximo 30 días y sin fechas pasadas");
    }
    public UUID ownCustomer(Scope s) {
        return db.sql("SELECT id FROM customers WHERE organization_id=:org AND user_id=:user AND active")
            .param("org",s.organizationId()).param("user",s.actorId()).query(UUID.class).optional().orElseThrow(() -> new ApiException(403,"Perfil de cliente no vinculado"));
    }
    @Transactional public Map<String,Object> create(Scope s,UUID customer,UUID vehicle,UUID space,Instant start,Instant end,UUID key) {
        validateInterval(start,end); data.lockBranch(s);
        if(s.role().equals("CUSTOMER")) customer=ownCustomer(s);
        var prior=db.sql("SELECT * FROM reservations WHERE organization_id=:org AND idempotency_key=:key").param("org",s.organizationId()).param("key",key).query().listOfRows().stream().findFirst();
        if(prior.isPresent()) {
            var p=prior.get();
            if(!p.get("branch_id").equals(s.branchId()) || !p.get("customer_id").equals(customer) || !p.get("vehicle_id").equals(vehicle) || !p.get("space_id").equals(space)
                || !((Timestamp)p.get("starts_at")).toInstant().equals(start) || !((Timestamp)p.get("ends_at")).toInstant().equals(end)) throw ApiException.conflict("Clave idempotente con otros datos");
            return p;
        }
        var v=db.sql("SELECT type FROM vehicles WHERE organization_id=:org AND id=:vehicle AND customer_id=:customer AND active AND EXISTS(SELECT 1 FROM customers c WHERE c.id=:customer AND c.organization_id=:org AND c.active)")
            .param("org",s.organizationId()).param("vehicle",vehicle).param("customer",customer).query(String.class).optional().orElseThrow(() -> new ApiException(422,"Vehículo no asociado al cliente activo"));
        if(availability(s,v,start,end).stream().noneMatch(p -> p.get("id").equals(space))) throw ApiException.conflict("El cupo ya no está disponible");
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO reservations(id,organization_id,branch_id,customer_id,vehicle_id,space_id,starts_at,ends_at,idempotency_key) VALUES(:id,:org,:branch,:customer,:vehicle,:space,:starts,:ends,:key)")
            .param("id",id).param("org",s.organizationId()).param("branch",s.branchId()).param("customer",customer).param("vehicle",vehicle).param("space",space).param("starts",Timestamp.from(start)).param("ends",Timestamp.from(end)).param("key",key).update();
        audit.record(s,"RESERVATION_CONFIRMED","reservations",id);events.emit(s,"ReservationConfirmed",id);return data.row(s,"reservations",id,false);
    }
    @Transactional public Map<String,Object> cancel(Scope s,UUID id) {
        data.lockBranch(s);var row=data.row(s,"reservations",id,true);
        if(s.role().equals("CUSTOMER") && !row.get("customer_id").equals(ownCustomer(s))) throw ApiException.notFound();
        if(row.get("status").equals("CANCELLED")) return row;
        if(!row.get("status").equals("CONFIRMED")) throw ApiException.conflict("La reserva no admite cancelación");
        db.sql("UPDATE reservations SET status='CANCELLED',updated_at=now() WHERE id=:id").param("id",id).update();audit.record(s,"RESERVATION_CANCELLED","reservations",id);return data.row(s,"reservations",id,false);
    }
    @Transactional public Map<String,Object> checkIn(Scope s,UUID id) {
        if(s.role().equals("CUSTOMER")) throw new ApiException(403,"Check-in requiere operador");
        data.lockBranch(s);var row=data.row(s,"reservations",id,true);
        if(row.get("status").equals("CHECKED_IN")) return row;
        if(!row.get("status").equals("CONFIRMED")) throw ApiException.conflict("Estado no permite ingreso");
        Instant now=Instant.now(),start=((Timestamp)row.get("starts_at")).toInstant(),end=((Timestamp)row.get("ends_at")).toInstant();
        if(now.isBefore(start) || !now.isBefore(end)) throw ApiException.conflict("Fuera del horario reservado");
        var session=parking.checkIn(s,(UUID)row.get("vehicle_id"),(UUID)row.get("space_id"),id,id);
        db.sql("UPDATE reservations SET status='CHECKED_IN',session_id=:session,updated_at=now() WHERE id=:id").param("session",session.get("id")).param("id",id).update();
        audit.record(s,"RESERVATION_CHECK_IN","reservations",id);return data.row(s,"reservations",id,false);
    }
}
