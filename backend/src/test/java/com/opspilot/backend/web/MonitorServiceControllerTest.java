package com.opspilot.backend.web;

import com.opspilot.backend.application.MonitoredServicesService;
import com.opspilot.backend.application.exception.MonitoredServiceNotFoundException;
import com.opspilot.backend.application.exception.OrganisationNotFoundException;
import com.opspilot.backend.config.SecurityConfig;
import com.opspilot.backend.domain.Environment;
import com.opspilot.backend.domain.MonitoredService;
import com.opspilot.backend.domain.Organisation;
import com.opspilot.backend.domain.ServiceType;
import com.opspilot.backend.domain.exception.MonitoredServiceAlreadyInStatusException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(MonitoredServiceController.class)
@Import(SecurityConfig.class)
public class MonitorServiceControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MonitoredServicesService monitoredServicesService;

    private MonitoredService createValidMonitoredService(
            UUID organisationId,
            UUID monitoredServiceId,
            boolean enabled
    ) {
        Organisation organisation = mock(Organisation.class);
        when(organisation.getId()).thenReturn(organisationId);

        MonitoredService monitoredService = mock(MonitoredService.class);
        when(monitoredService.getId()).thenReturn(monitoredServiceId);
        when(monitoredService.getOrganisation()).thenReturn(organisation);
        when(monitoredService.getName()).thenReturn("Payment API");
        when(monitoredService.getServiceType()).thenReturn(ServiceType.HTTP);
        when(monitoredService.getEnvironment()).thenReturn(Environment.DEVELOPMENT);
        when(monitoredService.getBaseUrl()).thenReturn("https://payment.example.com");
        when(monitoredService.getHealthEndpoint()).thenReturn("/health");
        when(monitoredService.getOwner()).thenReturn("Payments Team");
        when(monitoredService.isEnabled()).thenReturn(enabled);

        return monitoredService;
    }

    @Test
    void createMonitoredService_whenRequestIsValid_returnsCreated() throws Exception {
        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        UUID monitoredServiceId =
                UUID.fromString("00000000-0000-0000-0000-000000000002");

        MonitoredService monitoredService =
                createValidMonitoredService(
                        organisationId,
                        monitoredServiceId,
                        true
                );

        when(
                monitoredServicesService.createMonitoredService(
                        eq(organisationId),
                        eq("Payment API"),
                        eq(ServiceType.HTTP),
                        eq(Environment.DEVELOPMENT),
                        eq("https://payment.example.com"),
                        eq("/health"),
                        eq("Payments Team")
                )
        ).thenReturn(monitoredService);

        mockMvc.perform(
                        post(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services"
                        )
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "Payment API",
                                          "serviceType": "HTTP",
                                          "environment": "DEVELOPMENT",
                                          "baseUrl": "https://payment.example.com",
                                          "healthEndpoint": "/health",
                                          "owner": "Payments Team"
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.organisationId")
                                .value(organisationId.toString())
                )
                .andExpect(jsonPath("$.name").value("Payment API"))
                .andExpect(jsonPath("$.serviceType").value("HTTP"))
                .andExpect(
                        jsonPath("$.environment")
                                .value("DEVELOPMENT")
                )
                .andExpect(
                        jsonPath("$.baseUrl")
                                .value("https://payment.example.com")
                )
                .andExpect(
                        jsonPath("$.healthEndpoint")
                                .value("/health")
                )
                .andExpect(
                        jsonPath("$.owner")
                                .value("Payments Team")
                )
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void createMonitoredService_whenRequestIsInvalid_returnsBadRequest()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(
                        post(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services"
                        )
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "",
                                          "serviceType": null,
                                          "environment": null,
                                          "baseUrl": "",
                                          "healthEndpoint": "",
                                          "owner": ""
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value("Validation failed")
                )
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.serviceType").exists())
                .andExpect(jsonPath("$.errors.environment").exists())
                .andExpect(jsonPath("$.errors.baseUrl").exists())
                .andExpect(jsonPath("$.errors.healthEndpoint").exists())
                .andExpect(jsonPath("$.errors.owner").exists());

        verifyNoInteractions(monitoredServicesService);
    }

    @Test
    void createMonitoredService_whenJsonIsMalformed_returnsBadRequest()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(
                        post(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services"
                        )
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value("Malformed JSON request")
                )
                .andExpect(jsonPath("$.errors").isEmpty());

        verifyNoInteractions(monitoredServicesService);
    }

    @Test
    void createMonitoredService_whenOrganisationDoesNotExist_returnsNotFound()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(
                monitoredServicesService.createMonitoredService(
                        organisationId,
                        "Payment API",
                        ServiceType.HTTP,
                        Environment.DEVELOPMENT,
                        "https://payment.example.com",
                        "/health",
                        "Payments Team"
                )
        ).thenThrow(
                new OrganisationNotFoundException(organisationId)
        );

        mockMvc.perform(
                        post(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services"
                        )
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "Payment API",
                                          "serviceType": "HTTP",
                                          "environment": "DEVELOPMENT",
                                          "baseUrl": "https://payment.example.com",
                                          "healthEndpoint": "/health",
                                          "owner": "Payments Team"
                                        }
                                        """)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(
                        jsonPath("$.message").value(
                                "organisation with the id "
                                        + organisationId
                                        + " is not found"
                        )
                )
                .andExpect(jsonPath("$.errors").isEmpty());

        verify(monitoredServicesService)
                .createMonitoredService(
                        organisationId,
                        "Payment API",
                        ServiceType.HTTP,
                        Environment.DEVELOPMENT,
                        "https://payment.example.com",
                        "/health",
                        "Payments Team"
                );
    }

    @Test
    void getAllMonitoredServices_whenServicesExist_returnsOkAndServices()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        UUID monitoredServiceId =
                UUID.fromString("00000000-0000-0000-0000-000000000002");

        UUID secondMonitoredServiceId =
                UUID.fromString("00000000-0000-0000-0000-000000000003");

        MonitoredService monitoredService =
                createValidMonitoredService(
                        organisationId,
                        monitoredServiceId,
                        true
                );

        MonitoredService monitoredService1 =
                createValidMonitoredService(
                        organisationId,
                        secondMonitoredServiceId,
                        true
                );

        when(
                monitoredServicesService
                        .getAllByOrganisationId(organisationId)
        ).thenReturn(
                List.of(
                        monitoredService,
                        monitoredService1
                )
        );

        mockMvc.perform(
                        get(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services"
                        )
                                .with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(
                        jsonPath("$[0].organisationId")
                                .value(organisationId.toString())
                )
                .andExpect(
                        jsonPath("$[0].name")
                                .value("Payment API")
                )
                .andExpect(
                        jsonPath("$[0].serviceType")
                                .value("HTTP")
                )
                .andExpect(
                        jsonPath("$[0].environment")
                                .value("DEVELOPMENT")
                );

        verify(monitoredServicesService)
                .getAllByOrganisationId(organisationId);
    }

    @Test
    void getAllMonitoredServices_whenNoServicesExist_returnsOkAndEmptyList()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(
                monitoredServicesService
                        .getAllByOrganisationId(organisationId)
        ).thenReturn(List.of());

        mockMvc.perform(
                        get(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services"
                        )
                                .with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getMonitoredService_whenServiceExists_returnsOk()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        UUID monitoredServiceId =
                UUID.fromString("00000000-0000-0000-0000-000000000002");

        MonitoredService monitoredService =
                createValidMonitoredService(
                        organisationId,
                        monitoredServiceId,
                        true
                );

        when(
                monitoredServicesService.findById(
                        organisationId,
                        monitoredServiceId
                )
        ).thenReturn(monitoredService);

        mockMvc.perform(
                        get(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services/"
                                        + monitoredServiceId
                        )
                                .with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.id")
                                .value(monitoredServiceId.toString())
                )
                .andExpect(
                        jsonPath("$.organisationId")
                                .value(organisationId.toString())
                )
                .andExpect(
                        jsonPath("$.name")
                                .value("Payment API")
                )
                .andExpect(
                        jsonPath("$.serviceType")
                                .value("HTTP")
                )
                .andExpect(
                        jsonPath("$.environment")
                                .value("DEVELOPMENT")
                );

        verify(monitoredServicesService)
                .findById(
                        organisationId,
                        monitoredServiceId
                );
    }

    @Test
    void getMonitoredService_whenServiceDoesNotExist_returnsNotFound()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        UUID monitoredServiceId =
                UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(
                monitoredServicesService.findById(
                        organisationId,
                        monitoredServiceId
                )
        ).thenThrow(
                new MonitoredServiceNotFoundException(
                        monitoredServiceId
                )
        );

        mockMvc.perform(
                        get(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services/"
                                        + monitoredServiceId
                        )
                                .with(jwt())
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Monitored service with id "
                                                + monitoredServiceId
                                                + " was not found"
                                )
                )
                .andExpect(jsonPath("$.errors").isEmpty());

        verify(monitoredServicesService)
                .findById(
                        organisationId,
                        monitoredServiceId
                );
    }

    @Test
    void enableMonitoredService_whenServiceExists_returnsOkAndEnabledService()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        UUID monitoredServiceId =
                UUID.fromString("00000000-0000-0000-0000-000000000002");

        MonitoredService monitoredService =
                createValidMonitoredService(
                        organisationId,
                        monitoredServiceId,
                        true
                );

        when(
                monitoredServicesService.enable(
                        organisationId,
                        monitoredServiceId
                )
        ).thenReturn(monitoredService);

        mockMvc.perform(
                        patch(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services/"
                                        + monitoredServiceId
                                        + "/enable"
                        )
                                .with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.enabled")
                                .value(true)
                );

        verify(monitoredServicesService)
                .enable(
                        organisationId,
                        monitoredServiceId
                );
    }

    @Test
    void disableMonitoredService_whenServiceExists_returnsOkAndDisabledService()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        UUID monitoredServiceId =
                UUID.fromString("00000000-0000-0000-0000-000000000002");

        MonitoredService monitoredService =
                createValidMonitoredService(
                        organisationId,
                        monitoredServiceId,
                        false
                );

        when(
                monitoredServicesService.disable(
                        organisationId,
                        monitoredServiceId
                )
        ).thenReturn(monitoredService);

        mockMvc.perform(
                        patch(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services/"
                                        + monitoredServiceId
                                        + "/disable"
                        )
                                .with(jwt())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.enabled")
                                .value(false)
                );

        verify(monitoredServicesService)
                .disable(
                        organisationId,
                        monitoredServiceId
                );
    }

    @Test
    void enableMonitoredService_whenAlreadyEnabled_returnsConflict()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        UUID monitoredServiceId =
                UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(
                monitoredServicesService.enable(
                        organisationId,
                        monitoredServiceId
                )
        ).thenThrow(
                new MonitoredServiceAlreadyInStatusException(true)
        );

        mockMvc.perform(
                        patch(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services/"
                                        + monitoredServiceId
                                        + "/enable"
                        )
                                .with(jwt())
                )
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.status")
                                .value(409)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Monitored Service is already enabled"
                                )
                );

        verify(monitoredServicesService)
                .enable(
                        organisationId,
                        monitoredServiceId
                );
    }

    @Test
    void disableMonitoredService_whenAlreadyDisabled_returnsConflict()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        UUID monitoredServiceId =
                UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(
                monitoredServicesService.disable(
                        organisationId,
                        monitoredServiceId
                )
        ).thenThrow(
                new MonitoredServiceAlreadyInStatusException(false)
        );

        mockMvc.perform(
                        patch(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services/"
                                        + monitoredServiceId
                                        + "/disable"
                        )
                                .with(jwt())
                )
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.status")
                                .value(409)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Monitored Service is already disabled"
                                )
                );

        verify(monitoredServicesService)
                .disable(
                        organisationId,
                        monitoredServiceId
                );
    }

    @Test
    void getAllMonitoredServices_withoutAuthentication_returnsUnauthorized()
            throws Exception {

        UUID organisationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(
                        get(
                                "/api/organisations/"
                                        + organisationId
                                        + "/monitored-services"
                        )
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(monitoredServicesService);
    }
}