package com.opspilot.backend.domain;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganisationRepository
        extends ListCrudRepository<Organisation, UUID> {

    List<Organisation> findAllByName(String name);

    Optional<Organisation> findBySlug(String slug);
    Optional<Organisation> findById(UUID id);
    List<Organisation> findAllByStatus(OrganisationStatus status);

    boolean existsBySlug(String slug);

    // Serialize membership administration without blocking foreign-key KEY SHARE locks.
    @Query(value = "select * from organisations where id = :id for no key update", nativeQuery = true)
    Optional<Organisation> lockMembershipAdministration(@Param("id") UUID id);
}
