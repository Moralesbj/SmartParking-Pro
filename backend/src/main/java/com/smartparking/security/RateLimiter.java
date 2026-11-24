package com.smartparking.security;

import com.smartparking.common.errors.ApiException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limiter for sensitive endpoints (login, recovery, MFA).
 * In-memory per instance: complements the persistent per-account lockout stored in PostgreSQL.
 * A multi-instance deployment must move this to a shared store (documented in docs/security.md).
 */
@Component
public class RateLimiter {
    private final Map<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter() { this(Clock.systemUTC()); }
    RateLimiter(Clock clock) { this.clock = clock; }

    public void check(String bucket, String key, int max, long windowSeconds) {
        long now = clock.millis(), from = now - windowSeconds * 1000;
        var q = windows.computeIfAbsent(bucket + ":" + key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < from) q.pollFirst();
            if (q.size() >= max) throw new ApiException(429, "Demasiadas solicitudes. Intente más tarde");
            q.addLast(now);
        }
        if (windows.size() > 50_000) windows.entrySet().removeIf(e -> { synchronized (e.getValue()) { return e.getValue().isEmpty() || e.getValue().peekLast() < from; } });
    }
}
