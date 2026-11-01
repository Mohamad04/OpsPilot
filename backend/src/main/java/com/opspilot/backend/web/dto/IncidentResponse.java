package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.*;
import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(UUID id, UUID organisationId, UUID monitoredServiceId, String title, String description,
                               AlertSeverity severity, IncidentStatus status, UUID assignedUserId, Instant createdAt,
                               Instant acknowledgedAt, Instant resolvedAt, long version) {
    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(incident.getId(), incident.getOrganisationId(), incident.getMonitoredServiceId(),
                incident.getTitle(), incident.getDescription(), incident.getSeverity(), incident.getStatus(),
                incident.getAssignedUserId(), incident.getCreatedAt(), incident.getAcknowledgedAt(), incident.getResolvedAt(), incident.getVersion());
    }
}
