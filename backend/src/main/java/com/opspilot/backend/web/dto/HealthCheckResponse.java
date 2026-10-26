package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.HealthCheckResult;
import com.opspilot.backend.domain.HealthStatus;
import java.time.Instant;
import java.util.UUID;

public record HealthCheckResponse(UUID id, UUID organisationId, UUID monitoredServiceId, Instant startedAt,
                                  Instant completedAt, HealthStatus status, Integer httpStatusCode,
                                  long responseTimeMs, String failureDetail) {
    public static HealthCheckResponse from(HealthCheckResult result) {
        return new HealthCheckResponse(result.getId(), result.getOrganisationId(), result.getMonitoredServiceId(),
                result.getStartedAt(), result.getCompletedAt(), result.getStatus(), result.getHttpStatusCode(),
                result.getResponseTimeMs(), result.getFailureDetail());
    }
}
