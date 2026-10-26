package com.opspilot.backend.domain;

import org.springframework.data.repository.ListCrudRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends ListCrudRepository<Alert, UUID> {
    Optional<Alert> findByOrganisationIdAndMonitoredServiceIdAndCategoryAndStatus(
            UUID organisationId, UUID monitoredServiceId, AlertCategory category, AlertStatus status);
    List<Alert> findByOrganisationIdAndMonitoredServiceIdOrderByFirstSeenAtDesc(UUID organisationId, UUID monitoredServiceId);
}
