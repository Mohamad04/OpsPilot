package com.opspilot.backend.domain;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Testcontainers
public class OrganisationRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18.4-alpine");
    @Autowired
    private OrganisationRepository organisationRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void repositoryLoads() {
        assertNotNull(organisationRepository);
    }

    @Test
    void save_whenOrganisationIsValid_persistsOrganisation() {
        Organisation organisation =
                Organisation.create("Test", "test-organisation");

        Organisation savedOrganisation =
                organisationRepository.save(organisation);
        assertNotNull(savedOrganisation.getId());

        entityManager.flush();
        entityManager.clear();
        Optional<Organisation> foundOrganisation =
                organisationRepository.findById(savedOrganisation.getId());

        assertTrue(foundOrganisation.isPresent());
        assertEquals("Test", foundOrganisation.get().getName());
        assertEquals("test-organisation", foundOrganisation.get().getSlug());
    }

    @Test
    void save_whenSlugAlreadyExists_throwsConstraintViolationException() {
        Organisation first =
                Organisation.create("First", "test-organisation");

        Organisation second =
                Organisation.create("Second", "test-organisation");

        organisationRepository.save(first);
        entityManager.flush();
        entityManager.clear();

        organisationRepository.save(second);

        assertThrows(
                ConstraintViolationException.class,
                () -> entityManager.flush()
        );
    }

    @Test
    void findBySlug_whenSlugExists_returnsOrganisation() {
        Organisation organisation =
                Organisation.create("Test", "test-organisation");

        organisationRepository.save(organisation);

        entityManager.flush();
        entityManager.clear();

        Optional<Organisation> foundOrganisation =
                organisationRepository.findBySlug("test-organisation");

        assertTrue(foundOrganisation.isPresent());
        assertEquals("Test", foundOrganisation.get().getName());
        assertEquals("test-organisation", foundOrganisation.get().getSlug());
    }

    @Test
    void findBySlug_whenSlugDoesNotExist_returnsEmptyOptional() {
        Optional<Organisation> organisationFound =
                organisationRepository.findBySlug("not-found");

        assertTrue(organisationFound.isEmpty());
    }

    @Test
    void existsBySlug_whenSlugExists_returnsTrue() {
        Organisation organisation =
                Organisation.create("Test", "test-organisation");
        organisationRepository.save(organisation);
        entityManager.flush();
        entityManager.clear();
        boolean foundOrganisation = organisationRepository.existsBySlug("test-organisation");
        assertTrue(foundOrganisation);
    }
    @Test
    void existsBySlug_whenSlugDoesNotExist_returnsFalse() {
    boolean foundOrganisation =
            organisationRepository.existsBySlug("not-found");
    assertFalse(foundOrganisation);
    }
    @Test
    void findAllByStatus_whenOrganisationsMatch_returnsMatchingOrganisations() {
    Organisation  organisation1 =
            Organisation.create("Test", "test-organisation");
    Organisation  organisation2 =
            Organisation.create("Second", "test-organisation2");
    organisationRepository.save(organisation1);
    organisationRepository.save(organisation2);
    entityManager.flush();
    entityManager.clear();
    List<Organisation> organisationList = organisationRepository.findAllByStatus(
            OrganisationStatus.INACTIVE);
    assertEquals(2, organisationList.size());
        assertTrue(organisationList.stream()
                .anyMatch(o -> o.getSlug().equals("test-organisation")));

        assertTrue(organisationList.stream()
                .anyMatch(o -> o.getSlug().equals("test-organisation2")));
    }

    @Test
    void findAllByStatus_whenNoOrganisationsMatch_returnsEmptyList(){
        List <Organisation> organisationList = organisationRepository.findAllByStatus(
                OrganisationStatus.INACTIVE);
        assertTrue(organisationList.isEmpty());
    }

    @Test
    void findAllByName_whenOrganisationsMatch_returnsMatchingOrganisations() {
        Organisation organisation1 =
                Organisation.create("Test", "test-organisation");

        Organisation organisation2 =
                Organisation.create("Test", "test-organisation2");

        organisationRepository.save(organisation1);
        organisationRepository.save(organisation2);

        entityManager.flush();
        entityManager.clear();

        List<Organisation> organisationList =
                organisationRepository.findAllByName("Test");

        assertEquals(2, organisationList.size());

        assertTrue(organisationList.stream()
                .allMatch(o -> o.getName().equals("Test")));

        assertTrue(organisationList.stream()
                .anyMatch(o -> o.getSlug().equals("test-organisation")));

        assertTrue(organisationList.stream()
                .anyMatch(o -> o.getSlug().equals("test-organisation2")));
    }

    @Test
    void findAllByName_whenNoOrganisationsMatch_returnsEmptyList(){
        List <Organisation> organisationList = organisationRepository.findAllByName("Test");
        assertTrue(organisationList.isEmpty());
    }
}
