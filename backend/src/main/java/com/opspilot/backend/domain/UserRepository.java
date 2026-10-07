package com.opspilot.backend.domain;

import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends ListCrudRepository<User , UUID> {
    Optional<User> findByEmail(String email);

    Optional<User> findByIdentityProviderSubject(String identityProviderSubject);

}
