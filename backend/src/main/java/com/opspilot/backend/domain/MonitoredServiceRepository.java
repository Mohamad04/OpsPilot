package com.opspilot.backend.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

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

    // Lock the existing parent even when no alert rule exists yet. Held until the PUT transaction commits.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select service from MonitoredService service where service.id = :id and service.organisation.id = :organisationId")
    Optional<MonitoredService> findByIdAndOrganisationIdForUpdate(@Param("id") UUID id,
                                                               @Param("organisationId") UUID organisationId);

    List<MonitoredService> findAllByOrganisationIdAndEnabled(
            UUID organisationId,
            boolean enabled
    );

    List<MonitoredService> findAllByOrganisationIdAndEnvironment(
            UUID organisationId,
            Environment environment
    );

    List<MonitoredService> findAllByEnabled(boolean enabled);

    List<MonitoredService> findByEnabledTrueAndServiceType(ServiceType serviceType);
}
