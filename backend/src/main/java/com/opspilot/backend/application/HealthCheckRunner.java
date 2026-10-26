package com.opspilot.backend.application;

import com.opspilot.backend.config.MonitoringProperties;
import com.opspilot.backend.domain.HealthStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class HealthCheckRunner {
    private final HealthCheckStore store;
    private final HttpHealthProbe probe;
    private final MonitoringProperties properties;
    private final Clock clock;
    private final ConcurrentMap<UUID, Object> inFlight = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;

    public HealthCheckRunner(HealthCheckStore store, HttpHealthProbe probe, MonitoringProperties properties, Clock clock) {
        this.store = store;
        this.probe = probe;
        this.properties = properties;
        this.clock = clock;
        workers = new ThreadPoolExecutor(properties.workers(), properties.workers(), 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("health-probe-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public void check(UUID serviceId) {
        Object lock = new Object();
        if (inFlight.putIfAbsent(serviceId, lock) != null) return;
        boolean submitted = false;
        AtomicBoolean workerFinished = new AtomicBoolean();
        AtomicBoolean callerFinished = new AtomicBoolean();
        try {
            var target = store.loadTarget(serviceId);
            if (target.isEmpty()) return;
            Instant started = clock.instant();
            long startedNanos = System.nanoTime();
            CompletableFuture<HealthObservation> result = new CompletableFuture<>();
            HealthObservation observation;
            try {
                workers.execute(() -> {
                    try { result.complete(probe.check(target.get())); }
                    catch (RuntimeException exception) {
                        result.complete(failure(started, startedNanos, HealthStatus.UNKNOWN, "Health probe failed internally; no health observation is available."));
                    } finally {
                        workerFinished.set(true);
                        if (callerFinished.get()) inFlight.remove(serviceId, lock);
                    }
                });
                submitted = true;
                observation = result.get(properties.requestTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                // Native DNS resolution may outlive interruption. Keep the service lock until the worker actually exits.
                observation = failure(started, startedNanos, HealthStatus.DOWN, "HTTP check timed out.");
            } catch (RejectedExecutionException exception) {
                observation = failure(started, startedNanos, HealthStatus.UNKNOWN, "HTTP check capacity exhausted; no request was sent.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException exception) {
                observation = failure(started, startedNanos, HealthStatus.UNKNOWN, "Health probe failed internally; no health observation is available.");
            }
            store.record(target.get(), observation);
        } finally {
            callerFinished.set(true);
            if (!submitted || workerFinished.get()) inFlight.remove(serviceId, lock);
        }
    }

    private HealthObservation failure(Instant started, long startedNanos, HealthStatus status, String message) {
        Instant finished = clock.instant();
        if (finished.isBefore(started)) finished = started;
        return new HealthObservation(started, finished, status, null, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos), message);
    }

    @PreDestroy public void close() { workers.shutdownNow(); }
}
