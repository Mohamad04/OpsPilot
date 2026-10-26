package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.OrganisationMembershipNotFoundException;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.web.dto.MonitoredServiceResponse;
import com.opspilot.backend.web.dto.OrganisationResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Import({OrganisationMembershipService.class, OrganisationService.class,
        AuthenticatedUserService.class, MonitoredServicesService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ResponseDtoIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean AlertEvaluationEngine evaluator;
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");

    @Autowired OrganisationMembershipService memberships;
    @Autowired MonitoredServicesService services;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Environment environment;

    private String subject;
    private UUID organisationId;
    private UUID foreignOrganisationId;
    private UUID serviceId;

    @BeforeEach
    void persistFixtureInSeparateTransaction() {
        subject = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = User.create(subject, "Engineer", subject + "@example.com");
            entityManager.persist(user);
            Organisation organisation = Organisation.create("Acme", "acme-" + subject);
            Organisation second = Organisation.create("Beta", "beta-" + subject);
            Organisation foreign = Organisation.create("Foreign", "foreign-" + subject);
            entityManager.persist(organisation);
            entityManager.persist(second);
            entityManager.persist(foreign);
            entityManager.persist(OrganisationMembership.create(organisation, user, OrganisationRole.ENGINEER));
            entityManager.persist(OrganisationMembership.create(second, user, OrganisationRole.VIEWER));
            MonitoredService service = MonitoredService.create(organisation, "Payment API", ServiceType.HTTP,
                    com.opspilot.backend.domain.Environment.PRODUCTION,
                    "https://payment.example.com", "/health", "Payments");
            entityManager.persist(service);
            organisationId = organisation.getId();
            foreignOrganisationId = foreign.getId();
            serviceId = service.getId();
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("false", environment.getProperty("spring.jpa.open-in-view"));
    }

    @Test
    void organisationListContainsDetachedDtosWithoutNPlusOneQueries() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        List<OrganisationResponse> result = memberships.getOrganisations(subject);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(dto -> dto.id().equals(organisationId) && dto.name().equals("Acme")));
        assertTrue(result.stream().anyMatch(dto -> dto.name().equals("Beta")));
        assertTrue(result.stream().noneMatch(dto -> dto.id().equals(foreignOrganisationId)));
        assertEquals(2, statistics.getPrepareStatementCount(), "One user query and one membership/organisation query");
        String json = JsonMapper.builder().build().writeValueAsString(result);
        assertTrue(json.contains("Acme"));
        assertFalse(json.contains("identityProviderSubject"));
        assertFalse(json.contains("hibernateLazyInitializer"));
    }

    @Test
    void organisationDetailLoadsLazyFieldsBeforeTransactionCloses() {
        OrganisationResponse result = memberships.getOrganisation(subject, organisationId);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(organisationId, result.id());
        assertEquals("Acme", result.name());
        assertEquals("acme-" + subject, result.slug());
        assertEquals(OrganisationStatus.INACTIVE, result.status());
        assertNotNull(result.createdAt());
        assertThrows(OrganisationMembershipNotFoundException.class,
                () -> memberships.getOrganisation(subject, foreignOrganisationId));
    }

    @Test
    void creationReturnsDtoAndPersistsOwnerMembershipAtomically() {
        OrganisationResponse result = memberships.createOrganisationWithOwner(subject, "New", "new-" + subject);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("New", result.name());
        OrganisationResponse loaded = memberships.getOrganisation(subject, result.id());
        assertEquals(result.id(), loaded.id());
        assertEquals(result.name(), loaded.name());
        assertEquals(result.slug(), loaded.slug());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OrganisationMembership owner = entityManager.createQuery(
                            "select m from OrganisationMembership m where m.organisation.id = :id", OrganisationMembership.class)
                    .setParameter("id", result.id()).getSingleResult();
            assertEquals(OrganisationRole.OWNER, owner.getRole());
        });
    }

    @Test
    void monitoredServiceReadsAndWritesReturnDtosOutsidePersistenceContext() {
        MonitoredServiceResponse detail = services.findById(subject, organisationId, serviceId);
        assertEquals(organisationId, detail.organisationId());
        assertEquals("Payment API", detail.name());
        assertTrue(detail.enabled());
        assertEquals(List.of(detail), services.getAllByOrganisationId(subject, organisationId));
        assertFalse(services.disable(subject, organisationId, serviceId).enabled());
        assertTrue(services.enable(subject, organisationId, serviceId).enabled());
        MonitoredServiceResponse created = services.createMonitoredService(subject, organisationId, "New API",
                ServiceType.HTTP, com.opspilot.backend.domain.Environment.PRODUCTION,
                "https://new.example.com", "/health", "Payments");
        assertNotNull(created.id());
        assertEquals(created, services.findById(subject, organisationId, created.id()));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
