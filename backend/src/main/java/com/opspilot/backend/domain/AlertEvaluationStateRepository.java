package com.opspilot.backend.domain;

import org.springframework.data.repository.ListCrudRepository;
import java.util.Optional;
import java.util.UUID;

public interface AlertEvaluationStateRepository extends ListCrudRepository<AlertEvaluationState, UUID> {
    Optional<AlertEvaluationState> findByOrganisationIdAndMonitoredServiceId(UUID organisationId, UUID monitoredServiceId);
}
