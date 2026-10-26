package com.opspilot.backend.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.ListCrudRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HealthCheckResultRepository extends ListCrudRepository<HealthCheckResult, UUID> {
    Optional<HealthCheckResult> findFirstByOrganisationIdAndMonitoredServiceIdOrderByStartedAtDescIdDesc(UUID organisationId, UUID serviceId);
    List<HealthCheckResult> findByOrganisationIdAndMonitoredServiceIdOrderByStartedAtDescIdDesc(UUID organisationId, UUID serviceId, Pageable limit);
}
