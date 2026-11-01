package com.opspilot.backend.web;

import com.opspilot.backend.application.*;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/organisations/{organisationId}/incidents")
public class IncidentController {
    private final IncidentService incidents;
    public IncidentController(IncidentService incidents) { this.incidents = incidents; }

    @GetMapping
    public IncidentPageResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
            @RequestParam(required = false) IncidentStatus status, @RequestParam(required = false) AlertSeverity severity,
            @RequestParam(required = false) UUID serviceId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return incidents.list(jwt.getSubject(), organisationId, new IncidentQuery(status, severity, serviceId, page, size, sort));
    }

    @GetMapping("/{incidentId}")
    public IncidentResponse details(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId, @PathVariable UUID incidentId) {
        return incidents.details(jwt.getSubject(), organisationId, incidentId);
    }

    @GetMapping("/{incidentId}/events")
    public IncidentEventPageResponse timeline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
            @PathVariable UUID incidentId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return incidents.timeline(jwt.getSubject(), organisationId, incidentId, page, size);
    }

    @PatchMapping("/{incidentId}/status")
    public IncidentResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
            @PathVariable UUID incidentId, @Valid @RequestBody ChangeIncidentStatusRequest request) {
        return incidents.changeStatus(jwt.getSubject(), organisationId, incidentId, request.status(), request.expectedVersion());
    }

    @PatchMapping("/{incidentId}/assignee")
    public IncidentResponse assignee(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
            @PathVariable UUID incidentId, @Valid @RequestBody ChangeIncidentAssigneeRequest request) {
        return incidents.changeAssignee(jwt.getSubject(), organisationId, incidentId, request.assigneeUserId(), request.expectedVersion());
    }

    @PostMapping("/{incidentId}/comments")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public IncidentEventResponse comment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
            @PathVariable UUID incidentId, @Valid @RequestBody AddIncidentCommentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return incidents.addComment(jwt.getSubject(), organisationId, incidentId, request.content(), idempotencyKey);
    }
}
