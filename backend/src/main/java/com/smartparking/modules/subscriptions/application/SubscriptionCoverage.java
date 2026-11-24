package com.smartparking.modules.subscriptions.application;

import com.smartparking.common.tenancy.Scope;
import com.smartparking.modules.pricing.domain.CoverageWindows;
import com.smartparking.modules.pricing.domain.ParkingRateRequest.Period;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which monthly contracts apply to a vehicle in a branch, and which periods of a stay they pay for.
 * A contract applies when its plan is for the vehicle's category and authorizes the branch (the plan's own branch or
 * one listed in subscription_plan_branches). Only paid contracts count: ACTIVE, or EXPIRED while inside the plan's grace
 * days. PENDING (unpaid) and SUSPENDED contracts never cover anything.
 */
@Component
public class SubscriptionCoverage {
    private static final String ELIGIBLE = """
        FROM subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
        WHERE s.organization_id = :org AND s.vehicle_id = :vehicle AND p.vehicle_type = :type
          AND (p.branch_id = :branch OR EXISTS (SELECT 1 FROM subscription_plan_branches b WHERE b.plan_id = p.id AND b.branch_id = :branch))
        """;
    private final JdbcClient db;
    public SubscriptionCoverage(JdbcClient db) { this.db = db; }

    public record Status(String status, UUID subscriptionId, String planName, Instant startsAt, Instant endsAt, Instant graceUntil) {
        public static final Status NONE = new Status("NONE", null, null, null, null, null);
    }

    /** Periods inside [from, to) paid by the vehicle's contracts, in the branch time zone. */
    public List<Period> periods(Scope s, UUID vehicle, String type, Instant from, Instant to, ZoneId zone) {
        var contracts = db.sql("SELECT s.starts_at, s.ends_at + make_interval(days => p.grace_days) covered_until, p.allowed_days, p.allowed_start, p.allowed_end "
                + ELIGIBLE + " AND s.status IN ('ACTIVE','EXPIRED') AND s.starts_at < :to AND s.ends_at + make_interval(days => p.grace_days) > :from")
            .param("org", s.organizationId()).param("vehicle", vehicle).param("type", type).param("branch", s.branchId())
            .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
            .query((rs, i) -> new CoverageWindows.Contract(rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("covered_until").toInstant(),
                rs.getString("allowed_days"), localTime(rs.getTime("allowed_start")), localTime(rs.getTime("allowed_end")))).list();
        return CoverageWindows.covered(contracts, from, to, zone);
    }

    /** Contract that makes the vehicle a monthly customer at this instant, if any. */
    public Optional<UUID> validAt(Scope s, UUID vehicle, String type, Instant at) {
        return db.sql("SELECT s.id " + ELIGIBLE + " AND s.status IN ('ACTIVE','EXPIRED') AND s.starts_at <= :at AND s.ends_at + make_interval(days => p.grace_days) > :at ORDER BY s.ends_at DESC LIMIT 1")
            .param("org", s.organizationId()).param("vehicle", vehicle).param("type", type).param("branch", s.branchId()).param("at", Timestamp.from(at))
            .query(UUID.class).optional();
    }

    /** Most relevant contract for display: the one covering now, then the one whose dates include now (e.g. suspended), then the latest. */
    public Status status(Scope s, UUID vehicle, String type, Instant at) {
        return db.sql("""
            SELECT s.id, s.status, s.starts_at, s.ends_at, s.ends_at + make_interval(days => p.grace_days) grace_until, p.name,
                   (s.status IN ('ACTIVE','EXPIRED') AND s.starts_at <= :at AND s.ends_at + make_interval(days => p.grace_days) > :at) in_force,
                   (s.starts_at <= :at AND s.ends_at + make_interval(days => p.grace_days) > :at) current_dates
            """ + ELIGIBLE + " ORDER BY in_force DESC, current_dates DESC, s.ends_at DESC LIMIT 1")
            .param("org", s.organizationId()).param("vehicle", vehicle).param("type", type).param("branch", s.branchId()).param("at", Timestamp.from(at))
            .query((rs, i) -> {
                Instant starts = rs.getTimestamp("starts_at").toInstant(), ends = rs.getTimestamp("ends_at").toInstant(), grace = rs.getTimestamp("grace_until").toInstant();
                String stored = rs.getString("status");
                String shown = switch (stored) {
                    case "ACTIVE", "EXPIRED" -> at.isBefore(starts) ? "NOT_STARTED" : at.isBefore(ends) ? "ACTIVE" : at.isBefore(grace) ? "GRACE" : "EXPIRED";
                    default -> stored; // PENDING (unpaid) or SUSPENDED
                };
                return new Status(shown, rs.getObject("id", UUID.class), rs.getString("name"), starts, ends, grace);
            }).optional().orElse(Status.NONE);
    }

    private static java.time.LocalTime localTime(Time t) { return t == null ? null : t.toLocalTime(); }
}
