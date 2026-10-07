package com.opspilot.backend.domain;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

@DataJpaTest
@Testcontainers
public class OrganisationMembershipRepositoryTest {

    @Autowired
    private OrganisationMembershipRepository organisationMembershipRepository;

    @Autowired
    private OrganisationRepository organisationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Container
    @ServiceConnection
    static org.testcontainers.postgresql.PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18.4-alpine");

    private static User createUser(
            String identityProviderSubject,
            String displayName,
            String email
    ) {
        return User.create(
                identityProviderSubject,
                displayName,
                email
        );
    }

    private static OrganisationMembership createMembership(
            Organisation organisation,
            User user,
            OrganisationRole role
    ){
        return OrganisationMembership.create(
                organisation,
                user,
                role
        );
    }

    @Test
    void findByOrganisationIdAndUserId_returnsMembershipWhenExists() {
        User user = createUser(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );
        userRepository.save(user);
        Organisation organisation = Organisation.create("acme" , "slug");
        organisationRepository.save(organisation);
        OrganisationMembership organisationMembership = createMembership(
                organisation,
                user,
                OrganisationRole.ADMIN
        );
        organisationMembershipRepository.save(organisationMembership);
        testEntityManager.flush();
        testEntityManager.clear();
        Optional<OrganisationMembership> foundMembership = organisationMembershipRepository.findByOrganisationIdAndUserId(
                organisation.getId(),
                user.getId()
        );
        assertTrue(foundMembership.isPresent());

        OrganisationMembership found = foundMembership.get();

        assertEquals(OrganisationRole.ADMIN, found.getRole());
        assertEquals(user.getId(), found.getUser().getId());
        assertEquals(organisation.getId(), found.getOrganisation().getId());
    }

    @Test
    void findByOrganisationIdAndUserId_returnsEmptyWhenMembershipDoesNotExist(){
        User user = createUser(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );
        userRepository.save(user);
        Organisation organisation = Organisation.create("acme" , "slug");
        organisationRepository.save(organisation);
        testEntityManager.flush();
        testEntityManager.clear();
        Optional<OrganisationMembership> foundMembership =
                organisationMembershipRepository
                        .findByOrganisationIdAndUserId(
                organisation.getId(),
                                user.getId()
        );
        assertTrue(foundMembership.isEmpty());

    }

    @Test
    void duplicateMembershipForSameUserAndOrganisation_throwsException() {
        User user = createUser(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );
        userRepository.save(user);

        Organisation organisation = Organisation.create(
                "acme",
                "slug"
        );
        organisationRepository.save(organisation);

        OrganisationMembership organisationMembership1 = createMembership(
                organisation,
                user,
                OrganisationRole.ADMIN
        );

        organisationMembershipRepository.save(organisationMembership1);
        testEntityManager.flush();

        OrganisationMembership organisationMembership2 = createMembership(
                organisation,
                user,
                OrganisationRole.ENGINEER
        );

        organisationMembershipRepository.save(organisationMembership2);

        assertThrows(
                ConstraintViolationException.class,
                () -> testEntityManager.flush()
        );
    }

    @Test
    void findAllByOrganisationId_returnsMembershipsWhenTheyExist() {
        User user = createUser(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );
        userRepository.save(user);
        User user1 = createUser(
                "identity-12",
                "Mohamad1",
                "mohamad2@example.com"
        );
        userRepository.save(user1);

        Organisation organisation = Organisation.create(
                "acme",
                "slug"
        );
        organisationRepository.save(organisation);

        OrganisationMembership organisationMembership1 = createMembership(
                organisation,
                user,
                OrganisationRole.ADMIN
        );
        OrganisationMembership organisationMembership2 = createMembership(
                organisation,
                user1,
                OrganisationRole.ENGINEER
        );

        organisationMembershipRepository.save(organisationMembership1);
        organisationMembershipRepository.save(organisationMembership2);
        User user2 = createUser(
                "identity-1234",
                "Mohamad2",
                "mohamad3@example.com"
        );
        userRepository.save(user2);

        Organisation organisation2 = Organisation.create(
                "acme",
                "slug2"
        );
        organisationRepository.save(organisation2);

        OrganisationMembership organisationMembership3 = createMembership(
                organisation2,
                user2,
                OrganisationRole.ADMIN
        );

        organisationMembershipRepository.save(organisationMembership3);
        testEntityManager.flush();
        testEntityManager.clear();

        List<OrganisationMembership> foundMemberships =
                organisationMembershipRepository
                        .findAllByOrganisationId(
                                organisation.getId()
                        );
        assertTrue(foundMemberships.size() == 2);

        List<OrganisationMembership> foundMemberships2 =
                organisationMembershipRepository
                        .findAllByOrganisationId(
                                organisation2.getId()
                        );
        assertTrue(foundMemberships2.size() == 1);
    }

    @Test
    void findAllByUserId_returnsMembershipsWhenTheyExist() {
        User user = createUser(
                "identity-123",
                "Mohamad",
                "mohamad@example.com"
        );
        userRepository.save(user);
        Organisation organisation =
                Organisation
                        .create(
                                "slug",
                                "slug"
                        );
        organisationRepository.save(organisation);

        Organisation organisation1 = Organisation.create(
                "slug1",
                "slug2"
        );
        organisationRepository.save(organisation1);

        OrganisationMembership organisationMembership1 =
                createMembership(
                        organisation,
                        user,
                        OrganisationRole.ENGINEER
                );
        organisationMembershipRepository.save(organisationMembership1);

        OrganisationMembership organisationMembership2 =
                createMembership(
                        organisation1,
                        user,
                        OrganisationRole.ADMIN
                );
        organisationMembershipRepository.save(organisationMembership2);
        testEntityManager.flush();
        testEntityManager.clear();

        List<OrganisationMembership> foundMemberships =
                organisationMembershipRepository.findAllByUserId(
                        user.getId()
                );

        assertEquals(2, foundMemberships.size());
    }
}