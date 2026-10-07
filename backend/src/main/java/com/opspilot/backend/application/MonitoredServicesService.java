package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.MonitoredServiceNotFoundException;
import com.opspilot.backend.application.exception.OrganisationNotFoundException;
import com.opspilot.backend.domain.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
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

    public MonitoredService createMonitoredService(String subject,
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

        return  monitoredServiceRepository.save(monitoredService);
    }

    public MonitoredService findById(
            String subject,
            UUID organisationId,
            UUID monitoredServiceId) {
        requireMembership(subject, organisationId);
        return findScopedService(organisationId, monitoredServiceId);
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

    public MonitoredService disable(
            String subject,
            UUID organisationId,
            UUID monitoredServiceId) {
        requireWritePermission(subject, organisationId);

        MonitoredService monitoredService =
                findScopedService(organisationId, monitoredServiceId);

        monitoredService.disable();

        return monitoredServiceRepository.save(monitoredService);
    }

    public MonitoredService enable(
            String subject,
            UUID organisationId,
            UUID monitoredServiceId) {
        requireWritePermission(subject, organisationId);

        MonitoredService monitoredService =
                findScopedService(organisationId, monitoredServiceId);

        monitoredService.enable();

        return monitoredServiceRepository.save(monitoredService);
    }

    public List<MonitoredService> getAllByOrganisationId(String subject, UUID organisationId) {
        requireMembership(subject, organisationId);
        return monitoredServiceRepository.findAllByOrganisationId(organisationId);
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
