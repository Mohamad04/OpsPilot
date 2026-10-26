package com.opspilot.backend.domain;

import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface AlertRuleRepository extends ListCrudRepository<AlertRule, UUID> {
    Optional<AlertRule> findByOrganisationIdAndMonitoredServiceId(UUID organisationId, UUID monitoredServiceId);
}
