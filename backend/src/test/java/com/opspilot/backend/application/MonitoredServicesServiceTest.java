package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.MonitoredServiceNotFoundException;
import com.opspilot.backend.application.exception.OrganisationNotFoundException;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.web.dto.MonitoredServiceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class MonitoredServicesServiceTest {
    @Mock
    MonitoredServiceRepository  monitoredServiceRepository;

    @Mock
    OrganisationRepository organisationRepository;

    @Mock
    AuthenticatedUserService authenticatedUserService;

    @Mock
    OrganisationMembershipService organisationMembershipService;

    @BeforeEach
    void authenticatedUser() {
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(UUID.randomUUID());
        when(authenticatedUserService.findByIdentityProviderSubject("user")).thenReturn(user);
    }

    @InjectMocks
    MonitoredServicesService monitoredServicesService;


    private static MonitoredService createValidMonitoredService(Organisation organisation) {
        return MonitoredService.create(
                organisation, "Payment API", ServiceType.HTTP,
                Environment.DEVELOPMENT, "https://payment.example.com",
                "/health", "Payments Team"
        );
    }

    @Test
    void createMonitoredService_whenOrganisationExists_savesMonitoredService() {
        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        Organisation organisation =
                Organisation.create("acme", "acme");

        when(organisationRepository.findById(organisationId))
                .thenReturn(Optional.of(organisation));

        when(monitoredServiceRepository.save(any(MonitoredService.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MonitoredServiceResponse result =
                monitoredServicesService.createMonitoredService(
                        "user", organisationId,
                        "Payment API",
                        ServiceType.HTTP,
                        Environment.DEVELOPMENT,
                        "https://payment.example.com",
                        "/health",
                        "Payments Team"
                );

        assertEquals(organisation.getId(), result.organisationId());
        assertEquals("Payment API", result.name());
        assertEquals(ServiceType.HTTP, result.serviceType());
        assertEquals(Environment.DEVELOPMENT, result.environment());
        assertEquals("https://payment.example.com", result.baseUrl());
        assertEquals("/health", result.healthEndpoint());
        assertEquals("Payments Team", result.owner());
        assertTrue(result.enabled());

        verify(organisationRepository).findById(organisationId);
        verify(monitoredServiceRepository).save(any(MonitoredService.class));
    }

    @Test
    void createMonitoredService_whenOrganisationDoesNotExist_throwsOrganisationNotFoundException() {
        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(organisationRepository.findById(organisationId))
                .thenReturn(Optional.empty());

        assertThrows(
                OrganisationNotFoundException.class,
                () -> monitoredServicesService.createMonitoredService(
                        "user", organisationId,
                        "Payment API",
                        ServiceType.HTTP,
                        Environment.DEVELOPMENT,
                        "https://payment.example.com",
                        "/health",
                        "Payments Team"
                )
        );
        verify(organisationRepository).findById(organisationId);
        verify(monitoredServiceRepository, never())
                .save(any(MonitoredService.class));
    }




    @Test
    void findById_whenServiceExistsInOrganisation_returnsMonitoredService() {
        UUID organisationId = UUID.randomUUID();
        UUID monitoredServiceId = UUID.randomUUID();
        Organisation organisation = Organisation.create("acme", "acme");
        MonitoredService monitoredService = createValidMonitoredService(organisation);

        when(monitoredServiceRepository.findByIdAndOrganisationId(
                monitoredServiceId, organisationId))
                .thenReturn(Optional.of(monitoredService));

        MonitoredServiceResponse result = monitoredServicesService.findById(
                "user", organisationId, monitoredServiceId);

        assertEquals("Payment API", result.name());
        verify(monitoredServiceRepository).findByIdAndOrganisationId(
                monitoredServiceId, organisationId);
    }

    @Test
    void findById_whenServiceDoesNotExistInOrganisation_throwsMonitoredServiceNotFoundException() {
        UUID organisationId = UUID.randomUUID();
        UUID monitoredServiceId = UUID.randomUUID();

        when(monitoredServiceRepository.findByIdAndOrganisationId(
                monitoredServiceId, organisationId))
                .thenReturn(Optional.empty());

        MonitoredServiceNotFoundException exception = assertThrows(
                MonitoredServiceNotFoundException.class,
                () -> monitoredServicesService.findById(
                        "user", organisationId, monitoredServiceId));

        assertTrue(exception.getMessage().contains(monitoredServiceId.toString()));
        verify(monitoredServiceRepository).findByIdAndOrganisationId(
                monitoredServiceId, organisationId);
    }

    @Test
    void disable_whenServiceBelongsToOrganisation_disablesAndSavesService() {
        UUID organisationId = UUID.randomUUID();
        UUID monitoredServiceId = UUID.randomUUID();
        MonitoredService monitoredService = createValidMonitoredService(
                Organisation.create("acme", "acme"));

        when(monitoredServiceRepository.findByIdAndOrganisationId(
                monitoredServiceId, organisationId))
                .thenReturn(Optional.of(monitoredService));
        when(monitoredServiceRepository.save(monitoredService))
                .thenReturn(monitoredService);

        MonitoredServiceResponse result = monitoredServicesService.disable(
                "user", organisationId, monitoredServiceId);

        assertEquals("Payment API", result.name());
        assertFalse(result.enabled());
        verify(monitoredServiceRepository).findByIdAndOrganisationId(
                monitoredServiceId, organisationId);
        verify(monitoredServiceRepository).save(monitoredService);
    }

    @Test
    void disable_whenServiceDoesNotBelongToOrganisation_throwsMonitoredServiceNotFoundException() {
        UUID organisationId = UUID.randomUUID();
        UUID monitoredServiceId = UUID.randomUUID();

        when(monitoredServiceRepository.findByIdAndOrganisationId(
                monitoredServiceId, organisationId))
                .thenReturn(Optional.empty());

        assertThrows(MonitoredServiceNotFoundException.class,
                () -> monitoredServicesService.disable(
                        "user", organisationId, monitoredServiceId));

        verify(monitoredServiceRepository).findByIdAndOrganisationId(
                monitoredServiceId, organisationId);
        verify(monitoredServiceRepository, never())
                .save(any(MonitoredService.class));
    }

    @Test
    void enable_whenServiceBelongsToOrganisation_enablesAndSavesService() {
        UUID organisationId = UUID.randomUUID();
        UUID monitoredServiceId = UUID.randomUUID();
        MonitoredService monitoredService = createValidMonitoredService(
                Organisation.create("acme", "acme"));
        monitoredService.disable();

        when(monitoredServiceRepository.findByIdAndOrganisationId(
                monitoredServiceId, organisationId))
                .thenReturn(Optional.of(monitoredService));
        when(monitoredServiceRepository.save(monitoredService))
                .thenReturn(monitoredService);

        MonitoredServiceResponse result = monitoredServicesService.enable(
                "user", organisationId, monitoredServiceId);

        assertEquals("Payment API", result.name());
        assertTrue(result.enabled());
        verify(monitoredServiceRepository).findByIdAndOrganisationId(
                monitoredServiceId, organisationId);
        verify(monitoredServiceRepository).save(monitoredService);
    }

    @Test
    void enable_whenServiceDoesNotBelongToOrganisation_throwsMonitoredServiceNotFoundException() {
        UUID organisationId = UUID.randomUUID();
        UUID monitoredServiceId = UUID.randomUUID();

        when(monitoredServiceRepository.findByIdAndOrganisationId(
                monitoredServiceId, organisationId))
                .thenReturn(Optional.empty());

        assertThrows(MonitoredServiceNotFoundException.class,
                () -> monitoredServicesService.enable(
                        "user", organisationId, monitoredServiceId));

        verify(monitoredServiceRepository).findByIdAndOrganisationId(
                monitoredServiceId, organisationId);
        verify(monitoredServiceRepository, never())
                .save(any(MonitoredService.class));
    }

    @Test
    void getAllByOrganisationId_whenServicesExist_returnsServices() {
        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        Organisation organisation =
                Organisation.create("acme", "acme");

        MonitoredService service1 = createValidMonitoredService(organisation);
        MonitoredService service2 = createValidMonitoredService(organisation);

        when(monitoredServiceRepository.findAllByOrganisationId(organisationId))
                .thenReturn(List.of(service1, service2));

        List<MonitoredServiceResponse> result =
                monitoredServicesService.getAllByOrganisationId("user", organisationId);

        assertEquals(2, result.size());
        assertEquals("Payment API", result.get(0).name());
        assertEquals("Payment API", result.get(1).name());

        verify(monitoredServiceRepository)
                .findAllByOrganisationId(organisationId);
    }

    @Test
    void getAllByOrganisationId_whenNoServicesExist_returnsEmptyList() {
        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(monitoredServiceRepository.findAllByOrganisationId(organisationId))
                .thenReturn(List.of());

        List<MonitoredServiceResponse> result =
                monitoredServicesService.getAllByOrganisationId("user", organisationId);

        assertTrue(result.isEmpty());

        verify(monitoredServiceRepository)
                .findAllByOrganisationId(organisationId);
    }
}

