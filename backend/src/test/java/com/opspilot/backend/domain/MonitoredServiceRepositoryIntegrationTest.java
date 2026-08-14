package com.opspilot.backend.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;



@DataJpaTest
@Testcontainers
public class MonitoredServiceRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18.4-alpine");

    @Autowired
    private MonitoredServiceRepository monitoredServiceRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private OrganisationRepository organisationRepository;

    private static MonitoredService createValidMonitoredService(Organisation organisation) {
        return MonitoredService.create(
                organisation, "Payment API", ServiceType.HTTP,
                Environment.DEVELOPMENT, "https://payment.example.com",
                "/health", "Payments Team"
        );
    }

    @Test
    void repositoryLoad(){assertNotNull(monitoredServiceRepository);}

    @Test
    void save_whenMonitoredServiceIsValid_persistsMonitoredService() {
        Organisation organisation = Organisation.create("acme", "acme");
        organisationRepository.save(organisation);

        MonitoredService monitoredService =
                createValidMonitoredService(organisation);

        monitoredServiceRepository.save(monitoredService);

        testEntityManager.flush();
        testEntityManager.clear();

        Optional<MonitoredService> foundMonitoredService =
                monitoredServiceRepository.findById(monitoredService.getId());

        assertTrue(foundMonitoredService.isPresent());

        MonitoredService found = foundMonitoredService.orElseThrow();

        assertEquals("Payment API", found.getName());
        assertEquals("https://payment.example.com", found.getBaseUrl());
        assertEquals("/health", found.getHealthEndpoint());
        assertTrue(found.isEnabled());

        assertEquals("acme", found.getOrganisation().getName());
        assertEquals("acme", found.getOrganisation().getSlug());
    }

    @Test
    void findAllByOrganisationId_whenServicesExist_returnsOnlyServicesFromThatOrganisation() {
        Organisation organisation1 = Organisation.create("acme", "acme");
        organisationRepository.save(organisation1);
        Organisation organisation2 = Organisation.create("acme2", "acme2");
        organisationRepository.save(organisation2);
        MonitoredService monitoredService1 = createValidMonitoredService(organisation1);
        MonitoredService monitoredService2 = createValidMonitoredService(organisation1);
        MonitoredService monitoredService3 = createValidMonitoredService(organisation2);
        monitoredServiceRepository.save(monitoredService1);
        monitoredServiceRepository.save(monitoredService2);
        monitoredServiceRepository.save(monitoredService3);
        testEntityManager.flush();
        testEntityManager.clear();
        List<MonitoredService> foundMonitoredServices =
                monitoredServiceRepository
                        .findAllByOrganisationId(organisation1.getId());
        List<MonitoredService> foundMonitoredServices2 =
                monitoredServiceRepository
                        .findAllByOrganisationId(organisation2.getId());
        assertEquals(2, foundMonitoredServices.size());
        assertEquals(1, foundMonitoredServices2.size());
        assertTrue(foundMonitoredServices.stream()
                .allMatch(service ->
                        service.getOrganisation().getId().equals(organisation1.getId())));

        assertTrue(foundMonitoredServices2.stream()
                .allMatch(service ->
                        service.getOrganisation().getId().equals(organisation2.getId())));

    }
    @Test
    void findAllByOrganisationIdAndEnabled_whenMatchingServicesExist_returnsOnlyMatchingServices() {
        Organisation organisation1 = Organisation.create("acme", "acme");
        organisationRepository.save(organisation1);
        MonitoredService monitoredService1 = createValidMonitoredService(organisation1);
        monitoredServiceRepository.save(monitoredService1);
        testEntityManager.flush();
        testEntityManager.clear();
        List<MonitoredService> foundMonitoredServices =
                 monitoredServiceRepository
                        .findAllByOrganisationIdAndEnabled(organisation1.getId(), true);
        assertEquals(1, foundMonitoredServices.size());

        assertTrue(foundMonitoredServices.stream()
                .allMatch(service ->
                        service.getOrganisation().getId().equals(organisation1.getId())));

        assertTrue(foundMonitoredServices.stream()
                .allMatch(MonitoredService::isEnabled));
    }
    @Test
    void findAllByOrganisationIdAndEnvironment_whenMatchingServicesExist_returnsOnlyMatchingServices() {
        Organisation organisation1 = Organisation.create("acme", "acme");
        organisationRepository.save(organisation1);
        MonitoredService monitoredService1 = createValidMonitoredService(organisation1);
        monitoredServiceRepository.save(monitoredService1);
        testEntityManager.flush();
        testEntityManager.clear();
        List<MonitoredService> foundMonitoredServices =
                monitoredServiceRepository
                        .findAllByOrganisationIdAndEnvironment(organisation1.getId(), Environment.DEVELOPMENT);
        assertEquals(1, foundMonitoredServices.size());
        assertTrue(foundMonitoredServices.stream()
        .allMatch(service ->
                service.getOrganisation().getId().equals(organisation1.getId())));
        assertTrue(foundMonitoredServices.stream()
                .allMatch(service ->
                        service.getEnvironment() == Environment.DEVELOPMENT));
    }

    @Test
    void findByIdAndOrganisationId_whenServiceBelongsToOrganisation_returnsService() {
        Organisation organisation = organisationRepository.save(
                Organisation.create("acme", "acme"));
        Organisation otherOrganisation = organisationRepository.save(
                Organisation.create("other", "other"));
        MonitoredService monitoredService = monitoredServiceRepository.save(
                createValidMonitoredService(organisation));

        testEntityManager.flush();
        testEntityManager.clear();

        assertTrue(monitoredServiceRepository.findByIdAndOrganisationId(
                monitoredService.getId(), organisation.getId()).isPresent());
        assertTrue(monitoredServiceRepository.findByIdAndOrganisationId(
                monitoredService.getId(), otherOrganisation.getId()).isEmpty());
    }

    @Test
    void findAllByEnabled_whenServicesExist_returnsOnlyMatchingServices() {
        Organisation organisation = organisationRepository.save(
                Organisation.create("acme", "acme"));
        MonitoredService enabledService = createValidMonitoredService(organisation);
        MonitoredService disabledService = createValidMonitoredService(organisation);
        disabledService.disable();
        monitoredServiceRepository.saveAll(List.of(enabledService, disabledService));

        testEntityManager.flush();
        testEntityManager.clear();

        List<MonitoredService> enabledServices =
                monitoredServiceRepository.findAllByEnabled(true);
        List<MonitoredService> disabledServices =
                monitoredServiceRepository.findAllByEnabled(false);

        assertEquals(1, enabledServices.size());
        assertTrue(enabledServices.getFirst().isEnabled());
        assertEquals(1, disabledServices.size());
        assertFalse(disabledServices.getFirst().isEnabled());
    }
}
