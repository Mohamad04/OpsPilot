package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.HealthStatus;
import java.util.UUID;

public record LatestHealthResponse(UUID organisationId, UUID monitoredServiceId, boolean enabled,
                                   HealthStatus status, HealthCheckResponse latestResult) { }
