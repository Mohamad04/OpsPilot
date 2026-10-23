package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.MonitoredServiceNotFoundException;
import com.opspilot.backend.application.exception.OrganisationNotFoundException;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.web.dto.MonitoredServiceResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MonitoredServicesService {
    private final MonitoredServiceRepository monitoredServiceRepository;
    private final OrganisationRepository organisationRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final OrganisationMembershipService organisationMembershipService;

    public MonitoredServicesService(
            MonitoredServiceRepository monitoredServiceRepository,
            OrganisationRepository organisationRepository,
            AuthenticatedUserService authenticatedUserService,
            OrganisationMembershipService organisationMembershipService) {

        this.monitoredServiceRepository = monitoredServiceRepository;
        this.organisationRepository = organisationRepository;
        this.authenticatedUserService = authenticatedUserService;
        this.organisationMembershipService = organisationMembershipService;
    }

    public MonitoredServiceResponse createMonitoredService(String subject,
                                                   UUID organisationId,
                                                   String name,
                                                   ServiceType serviceType,
                                                   Environment environment,
                                                   String baseUrl,
                                                   String healthEndpoint,
                                                   String owner) {
        requireWritePermission(subject, organisationId);
        Organisation organisation = organisationRepository.findById(organisationId)
                .orElseThrow(() -> new OrganisationNotFoundException(organisationId));
        MonitoredService monitoredService = MonitoredService.create(
                organisation, name, serviceType, environment,
                baseUrl, healthEndpoint, owner);

        return toResponse(monitoredServiceRepository.save(monitoredService));
    }

    @Transactional(readOnly = true)
    public MonitoredServiceResponse findById(
            String subject,
            UUID organisationId,
            UUID monitoredServiceId) {
        requireMembership(subject, organisationId);
        return toResponse(findScopedService(organisationId, monitoredServiceId));
    }

    private MonitoredService findScopedService(UUID organisationId, UUID monitoredServiceId) {

        return monitoredServiceRepository
                .findByIdAndOrganisationId(
                        monitoredServiceId,
                        organisationId
                )
                .orElseThrow(() ->
                        new MonitoredServiceNotFoundException(monitoredServiceId)
                );
    }

    public MonitoredServiceResponse disable(
            String subject,
            UUID organisationId,
            UUID monitoredServiceId) {
        requireWritePermission(subject, organisationId);

        MonitoredService monitoredService =
                findScopedService(organisationId, monitoredServiceId);

        monitoredService.disable();

        return toResponse(monitoredServiceRepository.save(monitoredService));
    }

    public MonitoredServiceResponse enable(
            String subject,
            UUID organisationId,
            UUID monitoredServiceId) {
        requireWritePermission(subject, organisationId);

        MonitoredService monitoredService =
                findScopedService(organisationId, monitoredServiceId);

        monitoredService.enable();

        return toResponse(monitoredServiceRepository.save(monitoredService));
    }

    @Transactional(readOnly = true)
    public List<MonitoredServiceResponse> getAllByOrganisationId(String subject, UUID organisationId) {
        requireMembership(subject, organisationId);
        return monitoredServiceRepository.findAllByOrganisationId(organisationId)
                .stream().map(this::toResponse).toList();
    }

    private MonitoredServiceResponse toResponse(MonitoredService service) {
        return new MonitoredServiceResponse(service.getId(), service.getOrganisation().getId(),
                service.getName(), service.getServiceType(), service.getEnvironment(), service.getBaseUrl(),
                service.getHealthEndpoint(), service.getOwner(), service.isEnabled());
    }

    private OrganisationMembership requireMembership(String subject, UUID organisationId) {
        User user = authenticatedUserService.findByIdentityProviderSubject(subject);
        return organisationMembershipService.requireMembership(organisationId, user.getId());
    }

    private void requireWritePermission(String subject, UUID organisationId) {
        User user = authenticatedUserService.findByIdentityProviderSubject(subject);
        organisationMembershipService.requireMonitoredServiceWritePermission(organisationId, user.getId());
    }
}
