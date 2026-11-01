package com.opspilot.backend.domain;

import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface IncidentAlertRepository extends Repository<IncidentAlert, UUID> {
    <S extends IncidentAlert> S save(S link);
    Optional<IncidentAlert> findByIdAndOrganisationId(UUID id, UUID organisationId);
    Optional<IncidentAlert> findFirstByOrganisationIdAndAlertIdOrderByIdAsc(UUID organisationId, UUID alertId);

    @Query("""
            select count(a) from Alert a where a.organisationId = :organisationId and a.status = :status
              and exists (select link.id from IncidentAlert link where link.organisationId = :organisationId
                and link.incidentId = :incidentId and link.alertId = a.id)
            """)
    long countAlertsByOrganisationIdAndIncidentIdAndStatus(@Param("organisationId") UUID organisationId,
            @Param("incidentId") UUID incidentId, @Param("status") AlertStatus status);
    Slice<IncidentAlert> findAllByOrganisationIdAndIncidentIdOrderByIdAsc(
            UUID organisationId, UUID incidentId, Pageable pageable);
}
