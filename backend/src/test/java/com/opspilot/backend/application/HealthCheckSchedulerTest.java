package com.opspilot.backend.application;

import com.opspilot.backend.config.MonitoringProperties;
import com.opspilot.backend.config.MonitoringConfiguration;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthCheckSchedulerTest {
    @Test void oneFailureDoesNotAbortOtherServicesOrFutureRounds() {
        var store = mock(HealthCheckStore.class);
        var runner = mock(HealthCheckRunner.class);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(store.enabledHttpServiceIds()).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(runner).check(first);
        var scheduler = new HealthCheckScheduler(store, runner);
        assertDoesNotThrow(scheduler::runChecks);
        assertDoesNotThrow(scheduler::runChecks);
        verify(runner, times(2)).check(second);
        verify(runner, times(2)).check(first);
    }

    @Test void serviceDiscoveryFailureDoesNotEscapeScheduler() {
        var store = mock(HealthCheckStore.class);
        when(store.enabledHttpServiceIds()).thenThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(new HealthCheckScheduler(store, mock(HealthCheckRunner.class))::runChecks);
    }

    @Test void schedulingUsesConfiguredFixedDelayAndInitialDelay() throws Exception {
        var scheduled = HealthCheckScheduler.class.getMethod("runChecks").getAnnotation(Scheduled.class);
        assertEquals("${opspilot.monitoring.interval-ms}", scheduled.fixedDelayString());
        assertEquals("${opspilot.monitoring.initial-delay-ms}", scheduled.initialDelayString());
    }

    @Test void unsafeOrUnboundedConfigurationIsRejected() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(HealthTargetPolicyTest.SETTINGS).isEmpty());
            assertFalse(validator.validate(new MonitoringProperties(0, -1, 0, Integer.MAX_VALUE, 0, List.of())).isEmpty());
            assertFalse(validator.validate(new MonitoringProperties(1000, 0, 2000, 1000, 1, List.of(80))).isEmpty());
            assertFalse(validator.validate(new MonitoringProperties(1000, 0, 100, 1000, 1, List.of(65536))).isEmpty());
        }
    }

    @Test void springActuallyRunsRepeatedChecksAtTheConfiguredInterval() {
        var store = mock(HealthCheckStore.class);
        var runner = mock(HealthCheckRunner.class);
        UUID service = UUID.randomUUID();
        when(store.enabledHttpServiceIds()).thenReturn(List.of(service));
        CountDownLatch rounds = new CountDownLatch(2);
        doAnswer(call -> { rounds.countDown(); return null; }).when(runner).check(service);
        context(store, runner).withPropertyValues("opspilot.monitoring.enabled=true").run(application -> {
            assertNull(application.getStartupFailure());
            assertTrue(rounds.await(8, TimeUnit.SECONDS));
            verify(runner, atLeast(2)).check(service);
        });
    }

    @Test void disabledConfigurationDoesNotRegisterOrRunTheScheduler() {
        var store = mock(HealthCheckStore.class);
        var runner = mock(HealthCheckRunner.class);
        context(store, runner).withPropertyValues("opspilot.monitoring.enabled=false").run(application -> {
            assertNull(application.getStartupFailure());
            assertTrue(application.getBeansOfType(HealthCheckScheduler.class).isEmpty());
            verifyNoInteractions(store, runner);
        });
    }

    @Test void socketDeadlineExecutorRemainsAvailableWhileTheScheduledCheckBlocks() {
        var store = mock(HealthCheckStore.class);
        var runner = mock(HealthCheckRunner.class);
        UUID service = UUID.randomUUID();
        when(store.enabledHttpServiceIds()).thenReturn(List.of(service));
        context(store, runner).withPropertyValues("opspilot.monitoring.enabled=true").run(application -> {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var deadlines = application.getBean(ScheduledExecutorService.class);
            doAnswer(call -> {
                try {
                    assertTrue(Thread.currentThread().getName().startsWith("health-scheduler-"));
                    assertTrue(deadlines.schedule(() -> true, 10, TimeUnit.MILLISECONDS).get(1, TimeUnit.SECONDS));
                } catch (Throwable exception) { failure.set(exception); }
                finally { checked.countDown(); }
                return null;
            }).when(runner).check(service);
            assertTrue(checked.await(8, TimeUnit.SECONDS));
            assertNull(failure.get());
        });
    }

    private ApplicationContextRunner context(HealthCheckStore store, HealthCheckRunner runner) {
        return new ApplicationContextRunner().withUserConfiguration(MonitoringConfiguration.class, HealthCheckScheduler.class)
                .withBean(HealthCheckStore.class, () -> store).withBean(HealthCheckRunner.class, () -> runner)
                .withPropertyValues("opspilot.monitoring.interval-ms=1000", "opspilot.monitoring.initial-delay-ms=0",
                        "opspilot.monitoring.connect-timeout-ms=100", "opspilot.monitoring.request-timeout-ms=500",
                        "opspilot.monitoring.workers=1", "opspilot.monitoring.allowed-ports=80,443");
    }
}
