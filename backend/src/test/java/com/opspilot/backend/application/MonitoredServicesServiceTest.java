package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.MonitoredServiceNotFoundException;
import com.opspilot.backend.application.exception.OrganisationNotFoundException;
import com.opspilot.backend.domain.*;
import org.junit.jupiter.api.Test;
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

        MonitoredService result =
                monitoredServicesService.createMonitoredService(
                        organisationId,
                        "Payment API",
                        ServiceType.HTTP,
                        Environment.DEVELOPMENT,
                        "https://payment.example.com",
                        "/health",
                        "Payments Team"
                );

        assertSame(organisation, result.getOrganisation());
        assertEquals("Payment API", result.getName());
        assertEquals(ServiceType.HTTP, result.getServiceType());
        assertEquals(Environment.DEVELOPMENT, result.getEnvironment());
        assertEquals("https://payment.example.com", result.getBaseUrl());
        assertEquals("/health", result.getHealthEndpoint());
        assertEquals("Payments Team", result.getOwner());
        assertTrue(result.isEnabled());

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
                        organisationId,
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

        MonitoredService result = monitoredServicesService.findById(
                organisationId, monitoredServiceId);

        assertSame(monitoredService, result);
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
                        organisationId, monitoredServiceId));

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

        MonitoredService result = monitoredServicesService.disable(
                organisationId, monitoredServiceId);

        assertSame(monitoredService, result);
        assertFalse(result.isEnabled());
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
                        organisationId, monitoredServiceId));

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

        MonitoredService result = monitoredServicesService.enable(
                organisationId, monitoredServiceId);

        assertSame(monitoredService, result);
        assertTrue(result.isEnabled());
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
                        organisationId, monitoredServiceId));

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

        List<MonitoredService> result =
                monitoredServicesService.getAllByOrganisationId(organisationId);

        assertEquals(2, result.size());
        assertSame(service1, result.get(0));
        assertSame(service2, result.get(1));

        verify(monitoredServiceRepository)
                .findAllByOrganisationId(organisationId);
    }

    @Test
    void getAllByOrganisationId_whenNoServicesExist_returnsEmptyList() {
        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(monitoredServiceRepository.findAllByOrganisationId(organisationId))
                .thenReturn(List.of());

        List<MonitoredService> result =
                monitoredServicesService.getAllByOrganisationId(organisationId);

        assertTrue(result.isEmpty());

        verify(monitoredServiceRepository)
                .findAllByOrganisationId(organisationId);
    }
}

