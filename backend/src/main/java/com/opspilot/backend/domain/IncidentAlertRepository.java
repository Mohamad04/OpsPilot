package com.opspilot.backend.domain;

import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface IncidentAlertRepository extends Repository<IncidentAlert, UUID> {
    <S extends IncidentAlert> S save(S link);
    Optional<IncidentAlert> findByIdAndOrganisationId(UUID id, UUID organisationId);
    Slice<IncidentAlert> findAllByOrganisationIdAndIncidentIdOrderByIdAsc(
            UUID organisationId, UUID incidentId, Pageable pageable);
}
