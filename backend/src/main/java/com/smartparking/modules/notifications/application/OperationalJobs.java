package com.smartparking.modules.notifications.application;

import com.smartparking.common.events.Outbox;
import com.smartparking.modules.subscriptions.application.SubscriptionService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Schedules only; the logic lives in services that tests call directly. Every step is idempotent. */
@Service
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OperationalJobs {
    private final NotificationService notifications; private final SubscriptionService subscriptions; private final DeviceMonitor devices; private final JdbcClient db;

    public OperationalJobs(NotificationService notifications, SubscriptionService subscriptions, DeviceMonitor devices, JdbcClient db) {
        this.notifications = notifications; this.subscriptions = subscriptions; this.devices = devices; this.db = db;
    }

    @Scheduled(fixedDelay = 15000) public void deliver() { notifications.deliverPending(); }

    @Scheduled(fixedDelay = 60000) @Transactional public void lifecycle() {
        subscriptions.processLifecycle();
        db.sql("UPDATE reservations SET status='NO_SHOW',updated_at=now() WHERE status='CONFIRMED' AND ends_at<=now()").update();
        devices.markOffline();
    }

    /** Marks devices without heartbeat as OFFLINE, raising one alert and one DeviceOffline event per transition. */
    @Service
    public static class DeviceMonitor {
        private final JdbcClient db; private final Outbox outbox;
        public DeviceMonitor(JdbcClient db, Outbox outbox) { this.db = db; this.outbox = outbox; }
        @Transactional public int markOffline() {
            var gone = db.sql("UPDATE devices SET status='OFFLINE',updated_at=now() WHERE status='ONLINE' AND last_heartbeat_at<now()-interval '2 minutes' RETURNING id,organization_id,branch_id")
                .query().listOfRows();
            for (var d : gone) {
                db.sql("INSERT INTO anomaly_alerts(id,organization_id,branch_id,type,detail,reference_id) VALUES(gen_random_uuid(),:o,:b,'DEVICE_OFFLINE','Dispositivo sin heartbeat reciente',:id) ON CONFLICT DO NOTHING")
                    .param("o", d.get("organization_id")).param("b", d.get("branch_id")).param("id", d.get("id")).update();
                outbox.emitSystem((UUID) d.get("organization_id"), (UUID) d.get("branch_id"), "DeviceOffline", (UUID) d.get("id"));
            }
            return gone.size();
        }
    }
}
