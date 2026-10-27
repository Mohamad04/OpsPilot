package com.opspilot.backend.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** Deliberately omits global findById/findAll/delete methods. Callers must also authorise membership. */
public interface IncidentRepository extends Repository<Incident, UUID> {
    <S extends Incident> S save(S incident);
    Optional<Incident> findByIdAndOrganisationId(UUID id, UUID organisationId);
    Page<Incident> findAllByOrganisationId(UUID organisationId, Pageable pageable);
}
