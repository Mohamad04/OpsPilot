package com.opspilot.backend.web;

import com.opspilot.backend.application.MonitoredServicesService;
import com.opspilot.backend.web.dto.CreateMonitoredServiceRequest;
import com.opspilot.backend.web.dto.MonitoredServiceResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class MonitoredServiceController {

    private final MonitoredServicesService monitoredServicesService;

    public MonitoredServiceController(MonitoredServicesService monitoredServicesService) {
        this.monitoredServicesService = monitoredServicesService;
    }

    @PostMapping("/api/organisations/{organisationId}/monitored-services")
    @ResponseStatus(HttpStatus.CREATED)
    public MonitoredServiceResponse createMonitoredService(
            @Valid @RequestBody CreateMonitoredServiceRequest request,
            @PathVariable UUID organisationId,
            @AuthenticationPrincipal Jwt jwt) {

        return monitoredServicesService.createMonitoredService(
                        jwt.getSubject(),
                        organisationId,
                        request.name(),
                        request.serviceType(),
                        request.environment(),
                        request.baseUrl(),
                        request.healthEndpoint(),
                        request.owner()
                );
    }

    @GetMapping("/api/organisations/{organisationId}/monitored-services")
    public List<MonitoredServiceResponse> getAllMonitoredServices(
            @PathVariable UUID organisationId,
            @AuthenticationPrincipal Jwt jwt) {
        return monitoredServicesService
                .getAllByOrganisationId(jwt.getSubject(), organisationId);
    }

    @GetMapping("/api/organisations/{organisationId}/monitored-services/{monitoredServiceId}")
    public MonitoredServiceResponse getMonitoredService(
            @PathVariable UUID organisationId,
            @PathVariable UUID monitoredServiceId,
            @AuthenticationPrincipal Jwt jwt) {
        return monitoredServicesService
                .findById(jwt.getSubject(), organisationId, monitoredServiceId);
    }

    @PatchMapping("/api/organisations/{organisationId}/monitored-services/{monitoredServiceId}/enable")
    public MonitoredServiceResponse enableMonitoredService(
            @PathVariable UUID organisationId,
            @PathVariable UUID monitoredServiceId,
            @AuthenticationPrincipal Jwt jwt) {

        return monitoredServicesService.enable(
                        jwt.getSubject(),
                        organisationId,
                        monitoredServiceId
                );
    }

    @PatchMapping("/api/organisations/{organisationId}/monitored-services/{monitoredServiceId}/disable")
    public MonitoredServiceResponse disableMonitoredService(
            @PathVariable UUID organisationId,
            @PathVariable UUID monitoredServiceId,
            @AuthenticationPrincipal Jwt jwt) {

        return monitoredServicesService.disable(
                        jwt.getSubject(),
                        organisationId,
                        monitoredServiceId
                );
    }
}
