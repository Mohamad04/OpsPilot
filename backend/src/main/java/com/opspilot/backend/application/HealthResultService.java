package com.opspilot.backend.application;

import com.opspilot.backend.domain.HealthCheckResultRepository;
import com.opspilot.backend.domain.HealthStatus;
import com.opspilot.backend.web.dto.HealthCheckResponse;
import com.opspilot.backend.web.dto.LatestHealthResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class HealthResultService {
    private final MonitoredServicesService services;
    private final HealthCheckResultRepository results;

    public HealthResultService(MonitoredServicesService services, HealthCheckResultRepository results) { this.services = services; this.results = results; }

    public LatestHealthResponse latest(String subject, UUID organisationId, UUID serviceId) {
        var service = services.findById(subject, organisationId, serviceId); // Existing membership + scoped-service guard.
        var latest = results.findFirstByOrganisationIdAndMonitoredServiceIdOrderByStartedAtDescIdDesc(organisationId, serviceId)
                .map(HealthCheckResponse::from).orElse(null);
        return new LatestHealthResponse(organisationId, serviceId, service.enabled(), latest == null ? HealthStatus.UNKNOWN : latest.status(), latest);
    }

    public List<HealthCheckResponse> history(String subject, UUID organisationId, UUID serviceId, int limit) {
        services.findById(subject, organisationId, serviceId);
        return results.findByOrganisationIdAndMonitoredServiceIdOrderByStartedAtDescIdDesc(organisationId, serviceId, PageRequest.of(0, limit))
                .stream().map(HealthCheckResponse::from).toList();
    }
}
