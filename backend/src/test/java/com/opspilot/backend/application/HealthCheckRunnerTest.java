package com.opspilot.backend.application;

import com.opspilot.backend.config.MonitoringProperties;
import com.opspilot.backend.domain.HealthStatus;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthCheckRunnerTest {
    private final HealthCheckStore store = mock(HealthCheckStore.class);
    private final HttpHealthProbe probe = mock(HttpHealthProbe.class);
    private final HealthCheckTarget target = new HealthCheckTarget(UUID.randomUUID(), UUID.randomUUID(), "https://example.test", "/health");
    private final Instant now = Instant.parse("2026-10-26T12:00:00Z");
    private final HealthObservation up = new HealthObservation(now, now, HealthStatus.UP, 200, 1, null);
    private final ExecutorService callers = Executors.newCachedThreadPool();
    private HealthCheckRunner runner;

    @BeforeEach void setup() {
        when(store.loadTarget(target.serviceId())).thenReturn(Optional.of(target));
        when(probe.check(target)).thenReturn(up);
        runner = new HealthCheckRunner(store, probe, new MonitoringProperties(60000, 0, 100, 250, 1, List.of(80, 443)), Clock.systemUTC());
    }
    @AfterEach void close() { runner.close(); callers.shutdownNow(); }

    @Test void noProbeOrResultForDisabledMissingOrUnsupportedService() {
        when(store.loadTarget(target.serviceId())).thenReturn(Optional.empty());
        runner.check(target.serviceId());
        verifyNoInteractions(probe);
        verify(store, never()).record(any(), any());
    }

    @Test void completesChecksAndPersistsEachObservation() {
        runner.check(target.serviceId());
        runner.check(target.serviceId());
        verify(store, times(2)).record(target, up);
    }

    @Test void concurrentChecksForTheSameServiceAreSkippedUntilPersistenceCompletes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return null; }).when(store).record(target, up);
        Future<?> first = callers.submit(() -> runner.check(target.serviceId()));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            runner.check(target.serviceId());
            verify(probe, times(1)).check(target);
        } finally { release.countDown(); }
        first.get(3, TimeUnit.SECONDS);
        runner.check(target.serviceId());
        verify(probe, times(2)).check(target);
    }

    @Test void timedOutWorkerKeepsItsLockAndCapacityBoundUntilItReallyExits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        when(probe.check(target)).thenAnswer(call -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); return up; } finally { finished.countDown(); }
        });
        try {
            Future<?> first = callers.submit(() -> runner.check(target.serviceId()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            first.get(2, TimeUnit.SECONDS);
            var result = ArgumentCaptor.forClass(HealthObservation.class);
            verify(store).record(eq(target), result.capture());
            assertEquals(HealthStatus.DOWN, result.getValue().status());
            assertEquals("HTTP check timed out.", result.getValue().failureDetail());
            runner.check(target.serviceId());
            verify(probe, times(1)).check(target);

            var other = new HealthCheckTarget(UUID.randomUUID(), target.organisationId(), target.baseUrl(), target.healthEndpoint());
            when(store.loadTarget(other.serviceId())).thenReturn(Optional.of(other));
            runner.check(other.serviceId());
            verify(store).record(eq(other), argThat(observation -> observation.status() == HealthStatus.UNKNOWN && observation.failureDetail().contains("capacity")));
            verify(probe, never()).check(other);
        } finally { release.countDown(); }
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        verify(store, times(2)).record(any(), any()); // Late success is never persisted as a second result.
    }

    @Test void unexpectedProbeFailureBecomesUnknownAndDoesNotLeakExceptionMessage() {
        when(probe.check(target)).thenThrow(new IllegalStateException("secret URL and credentials"));
        runner.check(target.serviceId());
        verify(store).record(eq(target), argThat(result -> result.status() == HealthStatus.UNKNOWN && !result.failureDetail().contains("secret")));
    }

    @Test void persistenceFailureReleasesServiceLockForTheNextRound() {
        doThrow(new IllegalStateException("database down")).doNothing().when(store).record(target, up);
        assertThrows(IllegalStateException.class, () -> runner.check(target.serviceId()));
        assertDoesNotThrow(() -> runner.check(target.serviceId()));
        verify(probe, times(2)).check(target);
    }
}
