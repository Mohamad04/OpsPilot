package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.*;
import java.time.Instant;
import java.util.UUID;

public record AlertResponse(UUID id, UUID organisationId, UUID monitoredServiceId,
                            AlertCategory category, AlertSeverity severity, AlertStatus status,
                            Instant firstSeenAt, Instant lastSeenAt, Instant resolvedAt, long occurrenceCount,
                            int failureThreshold, int recoveryThreshold, Integer resolvedAfterRecoveries,
                            UUID firstFailureHealthCheckResultId, UUID triggeringHealthCheckResultId,
                            UUID lastFailureHealthCheckResultId, UUID resolutionHealthCheckResultId,
                            Integer triggeringHttpStatusCode, long triggeringResponseTimeMs) {
    public static AlertResponse from(Alert alert) {
        return new AlertResponse(alert.getId(), alert.getOrganisationId(), alert.getMonitoredServiceId(),
                alert.getCategory(), alert.getSeverity(), alert.getStatus(), alert.getFirstSeenAt(),
                alert.getLastSeenAt(), alert.getResolvedAt(), alert.getOccurrenceCount(),
                alert.getFailureThreshold(), alert.getRecoveryThreshold(), alert.getResolvedAfterRecoveries(),
                alert.getFirstFailureHealthCheckResultId(), alert.getTriggeringHealthCheckResultId(),
                alert.getLastFailureHealthCheckResultId(), alert.getResolutionHealthCheckResultId(),
                alert.getTriggeringHttpStatusCode(), alert.getTriggeringResponseTimeMs());
    }
}
