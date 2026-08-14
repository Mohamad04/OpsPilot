package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.Environment;
import com.opspilot.backend.domain.ServiceType;

import java.util.UUID;

public record MonitoredServiceResponse(
        UUID id,
        UUID organisationId,
        String name,
        ServiceType serviceType,
        Environment environment,
        String baseUrl,
        String healthEndpoint,
        String owner,
        boolean enabled
) {
}
