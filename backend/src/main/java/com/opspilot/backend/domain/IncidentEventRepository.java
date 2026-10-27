package com.opspilot.backend.domain;

import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** Append/read surface only; no deletion or bulk mutation operations. */
public interface IncidentEventRepository extends Repository<IncidentEvent, UUID> {
    <S extends IncidentEvent> S save(S event);
    Optional<IncidentEvent> findByIdAndOrganisationId(UUID id, UUID organisationId);
    Slice<IncidentEvent> findAllByOrganisationIdAndIncidentIdOrderByOccurredAtAscIdAsc(
            UUID organisationId, UUID incidentId, Pageable pageable);
}
