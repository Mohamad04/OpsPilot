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

    public MonitoredServicesService(
            MonitoredServiceRepository monitoredServiceRepository,
            OrganisationRepository organisationRepository) {

        this.monitoredServiceRepository = monitoredServiceRepository;
        this.organisationRepository = organisationRepository;
    }

    public MonitoredService createMonitoredService(UUID organisationId,
                                                   String name,
                                                   ServiceType serviceType,
                                                   Environment environment,
                                                   String baseUrl,
                                                   String healthEndpoint,
                                                   String owner) {
        Organisation organisation = organisationRepository.findById(organisationId)
                .orElseThrow(() -> new OrganisationNotFoundException(organisationId));
        MonitoredService monitoredService = MonitoredService.create(
                organisation, name, serviceType, environment,
                baseUrl, healthEndpoint, owner);

        return  monitoredServiceRepository.save(monitoredService);
    }

    public MonitoredService findById(
            UUID organisationId,
            UUID monitoredServiceId) {

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
            UUID organisationId,
            UUID monitoredServiceId) {

        MonitoredService monitoredService =
                findById(organisationId, monitoredServiceId);

        monitoredService.disable();

        return monitoredServiceRepository.save(monitoredService);
    }

    public MonitoredService enable(
            UUID organisationId,
            UUID monitoredServiceId) {

        MonitoredService monitoredService =
                findById(organisationId, monitoredServiceId);

        monitoredService.enable();

        return monitoredServiceRepository.save(monitoredService);
    }

    public List<MonitoredService> getAllByOrganisationId(UUID organisationId) {
        return monitoredServiceRepository.findAllByOrganisationId(organisationId);
    }
}
