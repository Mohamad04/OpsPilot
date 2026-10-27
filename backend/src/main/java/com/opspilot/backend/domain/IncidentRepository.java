package com.opspilot.backend.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Deliberately omits global findById/findAll/delete methods. Callers must also authorise membership. */
public interface IncidentRepository extends Repository<Incident, UUID> {
    <S extends Incident> S save(S incident);
    Optional<Incident> findByIdAndOrganisationId(UUID id, UUID organisationId);
    Page<Incident> findAllByOrganisationId(UUID organisationId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Incident i where i.id = :id and i.organisationId = :organisationId")
    Optional<Incident> findByIdAndOrganisationIdForUpdate(@Param("id") UUID id, @Param("organisationId") UUID organisationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Incident> findFirstByOrganisationIdAndMonitoredServiceIdAndStatusInOrderByCreatedAtAscIdAsc(
            UUID organisationId, UUID monitoredServiceId, Collection<IncidentStatus> statuses);
}
