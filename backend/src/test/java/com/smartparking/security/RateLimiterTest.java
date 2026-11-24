package com.smartparking.security;

import static org.assertj.core.api.Assertions.*;

import com.smartparking.common.errors.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    private final AtomicLong now = new AtomicLong(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };

    @Test
    void blocksAfterLimitAndRecoversAfterWindow() {
        var limiter = new RateLimiter(clock);
        for (int i = 0; i < 3; i++) limiter.check("login-ip", "10.0.0.1", 3, 60);
        assertThatThrownBy(() -> limiter.check("login-ip", "10.0.0.1", 3, 60))
            .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).status()).isEqualTo(429);
        limiter.check("login-ip", "10.0.0.2", 3, 60); // other keys are independent
        now.addAndGet(61_000);
        limiter.check("login-ip", "10.0.0.1", 3, 60);
    }
}
