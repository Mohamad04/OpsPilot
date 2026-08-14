package com.opspilot.backend.domain;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitoredServiceRepository
        extends ListCrudRepository<MonitoredService, UUID> {

    List<MonitoredService> findAllByOrganisationId(UUID organisationId);
    Optional<MonitoredService> findByIdAndOrganisationId(
            UUID id,
            UUID organisationId
    );
    List<MonitoredService> findAllByOrganisationIdAndEnabled(
            UUID organisationId,
            boolean enabled
    );

    List<MonitoredService> findAllByOrganisationIdAndEnvironment(
            UUID organisationId,
            Environment environment
    );

    List<MonitoredService> findAllByEnabled(boolean enabled);
}
