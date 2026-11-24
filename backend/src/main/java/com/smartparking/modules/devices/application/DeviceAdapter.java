package com.smartparking.modules.devices.application;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Port to physical devices. Only the simulator exists: no vendor protocol is claimed or implemented.
 * A vendor adapter must implement this contract (bounded time, explicit result) before real barriers are connected.
 */
public interface DeviceAdapter {
    enum Outcome { SIMULATED_ACK, TIMEOUT, FAILED }
    Outcome send(UUID deviceId, String command, Duration timeout);

    @Component
    class Simulator implements DeviceAdapter {
        /** Test hook: artificial latency to exercise the timeout path. Zero in normal operation. */
        volatile long simulatedLatencyMillis = 0;
        @Override public Outcome send(UUID deviceId, String command, Duration timeout) {
            try {
                return CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(simulatedLatencyMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return Outcome.SIMULATED_ACK;
                }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) { return Outcome.TIMEOUT; }
            catch (Exception e) { return Outcome.FAILED; }
        }
        public void setSimulatedLatencyMillis(long ms) { this.simulatedLatencyMillis = ms; }
    }
}
