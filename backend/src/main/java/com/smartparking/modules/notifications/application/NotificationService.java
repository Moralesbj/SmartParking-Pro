package com.smartparking.modules.notifications.application;

import com.smartparking.common.errors.ApiException;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers outbox events to the internal inbox. Each event runs in its own transaction; a failure is retried
 * up to {@link #MAX_ATTEMPTS} times and then marked FAILED. Recipients are idempotent per (event, user, channel),
 * so a retry after a partial failure never duplicates notifications. External channels are not configured:
 * they are never reported as sent.
 */
@Service
public class NotificationService {
    public static final int MAX_ATTEMPTS = 5;
    private record Template(String code, String title, String body, String audience) {}
    // audience: CUSTOMER (owner of the aggregate), STAFF (supervisors and admins of the branch), BOTH, NONE.
    private static final Map<String, Template> TEMPLATES = Map.of(
        "ReservationConfirmed", new Template("RESERVATION_CONFIRMED", "Reserva confirmada", "Su reserva fue confirmada.", "CUSTOMER"),
        "SubscriptionExpiring", new Template("SUBSCRIPTION_EXPIRING", "Mensualidad por vencer", "La mensualidad vence en los próximos días. Renueve para mantener el beneficio.", "BOTH"),
        "DeviceOffline", new Template("DEVICE_OFFLINE", "Dispositivo sin conexión", "Un dispositivo dejó de enviar señales.", "STAFF"));

    private final JdbcClient db; private final TransactionTemplate tx;

    public NotificationService(JdbcClient db, PlatformTransactionManager tm) { this.db = db; this.tx = new TransactionTemplate(tm); }

    /** Processes a batch of pending events; returns how many were delivered. Safe to run concurrently (SKIP LOCKED). */
    public int deliverPending() {
        var ids = db.sql("SELECT id FROM outbox_events WHERE status='PENDING' ORDER BY created_at LIMIT 100").query(UUID.class).list();
        int delivered = 0;
        for (UUID id : ids) {
            try {
                Boolean ok = tx.execute(st -> deliver(id));
                if (Boolean.TRUE.equals(ok)) delivered++;
            } catch (RuntimeException e) {
                String msg = e.getClass().getSimpleName();
                tx.executeWithoutResult(st -> db.sql("UPDATE outbox_events SET attempts=attempts+1,last_error=:err,status=CASE WHEN attempts+1>=:max THEN 'FAILED' ELSE 'PENDING' END WHERE id=:id")
                    .param("err", msg).param("max", MAX_ATTEMPTS).param("id", id).update());
            }
        }
        return delivered;
    }

    private boolean deliver(UUID id) {
        var e = db.sql("SELECT * FROM outbox_events WHERE id=:id AND status='PENDING' FOR UPDATE SKIP LOCKED").param("id", id).query().listOfRows().stream().findFirst();
        if (e.isEmpty()) return false;
        var event = e.get();
        String type = (String) event.get("event_type");
        var template = TEMPLATES.get(type);
        if (template != null) {
            Set<UUID> recipients = new LinkedHashSet<>();
            if (template.audience().equals("CUSTOMER") || template.audience().equals("BOTH")) recipients.addAll(customerOf(type, (UUID) event.get("aggregate_id")));
            if (template.audience().equals("STAFF") || template.audience().equals("BOTH")) recipients.addAll(staff((UUID) event.get("organization_id"), (UUID) event.get("branch_id")));
            for (UUID user : recipients) {
                if (!enabled(user, type, "IN_APP")) continue;
                db.sql("""
                    INSERT INTO notifications(id,organization_id,branch_id,recipient_user_id,title,body,outbox_event_id,template_code,channel)
                    VALUES(gen_random_uuid(),:org,:branch,:user,:title,:body,:event,:code,'IN_APP')
                    ON CONFLICT (outbox_event_id,recipient_user_id,channel) WHERE outbox_event_id IS NOT NULL DO NOTHING
                    """).param("org", event.get("organization_id")).param("branch", event.get("branch_id")).param("user", user)
                    .param("title", template.title()).param("body", template.body()).param("event", id).param("code", template.code()).update();
            }
        }
        db.sql("UPDATE outbox_events SET status='DELIVERED',delivered_at=now(),attempts=attempts+1,last_error=NULL WHERE id=:id").param("id", id).update();
        return true;
    }

    private List<UUID> customerOf(String type, UUID aggregate) {
        String table = switch (type) { case "ReservationConfirmed" -> "reservations"; case "SubscriptionExpiring" -> "subscriptions"; default -> null; };
        if (table == null) return List.of();
        return db.sql("SELECT c.user_id FROM " + table + " a JOIN customers c ON c.id=a.customer_id AND c.organization_id=a.organization_id WHERE a.id=:id AND c.user_id IS NOT NULL")
            .param("id", aggregate).query(UUID.class).list();
    }

    private List<UUID> staff(UUID org, UUID branch) {
        return db.sql("SELECT DISTINCT user_id FROM user_memberships WHERE organization_id=:org AND (branch_id IS NULL OR branch_id=:branch) AND active AND role IN('ADMIN','SUPERADMIN','SUPERVISOR')")
            .param("org", org).param("branch", branch).query(UUID.class).list();
    }

    /** Opt-out model for the internal inbox; external channels would be opt-in (none configured). */
    private boolean enabled(UUID user, String type, String channel) {
        return db.sql("SELECT enabled FROM notification_preferences WHERE user_id=:u AND event_type=:t AND channel=:c")
            .param("u", user).param("t", type).param("c", channel).query(Boolean.class).optional().orElse(channel.equals("IN_APP"));
    }

    public List<Map<String, Object>> preferences(UUID user) {
        var stored = db.sql("SELECT event_type,channel,enabled FROM notification_preferences WHERE user_id=:u").param("u", user).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String type : new TreeSet<>(TEMPLATES.keySet())) {
            boolean enabled = stored.stream().filter(r -> r.get("event_type").equals(type) && r.get("channel").equals("IN_APP")).map(r -> (Boolean) r.get("enabled")).findFirst().orElse(true);
            out.add(Map.of("eventType", type, "channel", "IN_APP", "enabled", enabled, "providerConfigured", true));
            for (String ch : List.of("EMAIL", "SMS", "WHATSAPP")) out.add(Map.of("eventType", type, "channel", ch, "enabled", false, "providerConfigured", false));
        }
        return out;
    }

    public void setPreference(UUID user, String type, String channel, boolean enabled) {
        if (!TEMPLATES.containsKey(type)) throw new ApiException(422, "Tipo de evento desconocido");
        if (!channel.equals("IN_APP") && enabled) throw new ApiException(409, "El canal " + channel + " no tiene proveedor configurado", "CHANNEL_NOT_CONFIGURED");
        db.sql("""
            INSERT INTO notification_preferences(id,user_id,event_type,channel,enabled) VALUES(gen_random_uuid(),:u,:t,:c,:e)
            ON CONFLICT (user_id,event_type,channel) DO UPDATE SET enabled=EXCLUDED.enabled,updated_at=now()
            """).param("u", user).param("t", type).param("c", channel).param("e", enabled).update();
    }
}
