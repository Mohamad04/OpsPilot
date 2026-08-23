package com.opspilot.backend.domain;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OranisationMembershipRepository extends ListCrudRepository<UUID> {
    Optional<UUID> findByOrganisationIdAndUserId(UUID organisationId, UUID userId);
    List<OrganisationMembership> findAllByOrganisationId(UUID organisationId);
    List<OrganisationMembership> findAllByUserId(UUID userId);
}
