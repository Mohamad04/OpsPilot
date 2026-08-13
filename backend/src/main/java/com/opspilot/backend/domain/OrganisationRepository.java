package com.opspilot.backend.domain;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganisationRepository
        extends ListCrudRepository<Organisation, UUID> {

    List<Organisation> findAllByName(String name);

    Optional<Organisation> findBySlug(String slug);

    List<Organisation> findAllByStatus(OrganisationStatus status);

    boolean existsBySlug(String slug);
}