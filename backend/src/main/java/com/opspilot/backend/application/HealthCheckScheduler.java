package com.opspilot.backend.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "opspilot.monitoring.enabled", havingValue = "true", matchIfMissing = true)
public class HealthCheckScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckScheduler.class);
    private final HealthCheckStore store;
    private final HealthCheckRunner runner;

    public HealthCheckScheduler(HealthCheckStore store, HealthCheckRunner runner) { this.store = store; this.runner = runner; }

    @Scheduled(fixedDelayString = "${opspilot.monitoring.interval-ms}", initialDelayString = "${opspilot.monitoring.initial-delay-ms}")
    public void runChecks() {
        try {
            for (var serviceId : store.enabledHttpServiceIds()) {
                if (Thread.currentThread().isInterrupted()) break;
                try { runner.check(serviceId); }
                catch (RuntimeException exception) {
                    // Do not log target URLs, credentials, response bodies, or database error details.
                    LOG.warn("Health check could not complete for service {} ({})", serviceId, exception.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException exception) {
            LOG.warn("Health check round could not load services ({})", exception.getClass().getSimpleName());
        }
    }
}
