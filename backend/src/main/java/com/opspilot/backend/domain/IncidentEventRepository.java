package com.opspilot.backend.domain;

import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** Append/read surface only; no deletion or bulk mutation operations. */
public interface IncidentEventRepository extends Repository<IncidentEvent, UUID> {
    <S extends IncidentEvent> S save(S event);
    Optional<IncidentEvent> findByIdAndOrganisationId(UUID id, UUID organisationId);
    Optional<IncidentEvent> findByOrganisationIdAndIncidentIdAndActorUserIdAndClientRequestId(
            UUID organisationId, UUID incidentId, UUID actorUserId, UUID clientRequestId);
    Optional<IncidentEvent> findByOrganisationIdAndSourceAlertIdAndEventType(
            UUID organisationId, UUID sourceAlertId, IncidentEventType eventType);
    Optional<IncidentEvent> findFirstByOrganisationIdAndIncidentIdOrderByOccurredAtDescIdDesc(UUID organisationId, UUID incidentId);
    Page<IncidentEvent> findByOrganisationIdAndIncidentId(UUID organisationId, UUID incidentId, Pageable pageable);
    Slice<IncidentEvent> findAllByOrganisationIdAndIncidentIdOrderByOccurredAtAscIdAsc(
            UUID organisationId, UUID incidentId, Pageable pageable);
}
