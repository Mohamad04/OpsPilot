package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.*;
import java.time.Instant;
import java.util.UUID;

public record IncidentEventResponse(UUID id, UUID organisationId, UUID incidentId, IncidentEventType eventType,
                                    UUID actorUserId, UUID sourceAlertId, Instant occurredAt, String details,
                                    IncidentStatus previousStatus, IncidentStatus newStatus,
                                    UUID previousAssigneeUserId, UUID newAssigneeUserId) {
    public static IncidentEventResponse from(IncidentEvent event) {
        return new IncidentEventResponse(event.getId(), event.getOrganisationId(), event.getIncidentId(), event.getEventType(),
                event.getActorUserId(), event.getSourceAlertId(), event.getOccurredAt(), event.getDetails(), event.getPreviousStatus(), event.getNewStatus(),
                event.getPreviousAssigneeUserId(), event.getNewAssigneeUserId());
    }
}
