package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.temporal.ChronoUnit;

@Service
public class HealthCheckStore {
    private final MonitoredServiceRepository services;
    private final HealthCheckResultRepository results;
    private final AlertEvaluationEngine evaluator;

    public HealthCheckStore(MonitoredServiceRepository services, HealthCheckResultRepository results, AlertEvaluationEngine evaluator) {
        this.services = services;
        this.results = results;
        this.evaluator = evaluator;
    }

    @Transactional(readOnly = true)
    public List<UUID> enabledHttpServiceIds() {
        return services.findByEnabledTrueAndServiceType(ServiceType.HTTP).stream().map(MonitoredService::getId).toList();
    }

    @Transactional(readOnly = true)
    public Optional<HealthCheckTarget> loadTarget(UUID serviceId) {
        return services.findById(serviceId).filter(this::eligible)
                .map(service -> new HealthCheckTarget(service.getId(), service.getOrganisation().getId(), service.getBaseUrl(), service.getHealthEndpoint()));
    }

    @Transactional
    public void record(HealthCheckTarget target, HealthObservation observation) {
        // Discard a result if the service was removed, disabled, or its checked configuration changed.
        services.findByIdAndOrganisationIdForUpdate(target.serviceId(), target.organisationId())
                .filter(this::eligible)
                .filter(service -> service.getBaseUrl().equals(target.baseUrl()) && service.getHealthEndpoint().equals(target.healthEndpoint()))
                .ifPresent(service -> {
                    var result = results.save(new HealthCheckResult(target.organisationId(), target.serviceId(),
                            observation.startedAt().truncatedTo(ChronoUnit.MICROS), observation.completedAt().truncatedTo(ChronoUnit.MICROS),
                            observation.status(), observation.httpStatusCode(), observation.responseTimeMs(), observation.failureDetail()));
                    evaluator.evaluate(target.organisationId(), target.serviceId(), result.getId());
                });
    }

    private boolean eligible(MonitoredService service) { return service.isEnabled() && service.getServiceType() == ServiceType.HTTP; }
}
