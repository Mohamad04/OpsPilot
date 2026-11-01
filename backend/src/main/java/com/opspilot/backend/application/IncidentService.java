package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.*;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.domain.exception.InvalidIncidentTransitionException;
import com.opspilot.backend.web.dto.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class IncidentService {
    private final IncidentRepository incidents;
    private final IncidentEventRepository events;
    private final IncidentAlertRepository links;
    private final MonitoredServiceRepository services;
    private final AuthenticatedUserService authenticatedUsers;
    private final OrganisationMembershipService memberships;
    private final OrganisationMembershipRepository membershipRepository;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, IncidentEventRepository events, IncidentAlertRepository links,
                            MonitoredServiceRepository services, AuthenticatedUserService authenticatedUsers,
                            OrganisationMembershipService memberships, OrganisationMembershipRepository membershipRepository, Clock clock) {
        this.incidents = incidents; this.events = events; this.links = links; this.services = services;
        this.authenticatedUsers = authenticatedUsers; this.memberships = memberships;
        this.membershipRepository = membershipRepository; this.clock = clock;
    }

    public IncidentPageResponse list(String subject, UUID organisationId, IncidentQuery query) {
        member(subject, organisationId);
        if (query.serviceId() != null) services.findByIdAndOrganisationId(query.serviceId(), organisationId)
                .orElseThrow(() -> new MonitoredServiceNotFoundException(query.serviceId()));
        return IncidentPageResponse.from(incidents.searchByOrganisationId(organisationId, query.status(), query.severity(),
                query.serviceId(), query.pageable()).map(IncidentResponse::from));
    }

    public IncidentResponse details(String subject, UUID organisationId, UUID incidentId) {
        member(subject, organisationId);
        return IncidentResponse.from(find(organisationId, incidentId));
    }

    public IncidentEventPageResponse timeline(String subject, UUID organisationId, UUID incidentId, int page, int size) {
        IncidentQuery.validatePagination(page, size);
        member(subject, organisationId); find(organisationId, incidentId);
        return IncidentEventPageResponse.from(events.findByOrganisationIdAndIncidentId(organisationId, incidentId,
                PageRequest.of(page, size, Sort.by(Sort.Order.asc("occurredAt"), Sort.Order.asc("id")))).map(IncidentEventResponse::from));
    }

    @Transactional
    public IncidentResponse changeStatus(String subject, UUID organisationId, UUID incidentId, IncidentStatus target, long expectedVersion) {
        if (target == null || expectedVersion < 0) throw new InvalidIncidentQueryException("Status and nonnegative expectedVersion are required");
        OrganisationMembership actor = member(subject, organisationId);
        switch (actor.getRole()) {
            case OWNER, ADMIN, ENGINEER -> { }
            case VIEWER -> throw new InsufficientOrganisationPermissionException("VIEWER cannot change incident status");
        }
        Incident incident = lockIncident(organisationId, incidentId);
        if (incident.getVersion() != expectedVersion) throw new IncidentConflictException("Incident changed; reload it before retrying");
        if (!incident.getStatus().canTransitionTo(target)) throw new InvalidIncidentTransitionException(incident.getStatus(), target);
        if (!incident.getStatus().isOngoing() && target.isOngoing() && incident.getMonitoredServiceId() != null) {
            var ongoing = incidents.findFirstByOrganisationIdAndMonitoredServiceIdAndStatusInOrderByCreatedAtAscIdAsc(
                    organisationId, incident.getMonitoredServiceId(), Arrays.stream(IncidentStatus.values()).filter(IncidentStatus::isOngoing).toList());
            if (ongoing.isPresent() && !ongoing.get().getId().equals(incidentId)) {
                throw new IncidentConflictException("Another ongoing incident already exists for this monitored service");
            }
        }
        if ((target == IncidentStatus.RESOLVED || target == IncidentStatus.CLOSED)
                && links.countAlertsByOrganisationIdAndIncidentIdAndStatus(organisationId, incidentId, AlertStatus.ACTIVE) > 0) {
            throw new IncidentConflictException("Cannot resolve or close an incident while linked alerts are ACTIVE");
        }
        Instant at = transitionTime(incident);
        var previous = incident.getStatus(); var previousResolvedAt = incident.getResolvedAt();
        try {
            incident.changeStatus(target, at);
            incidents.save(incident);
            events.save(IncidentEvent.statusChanged(incident, previous, actor, at, previousResolvedAt));
            incidents.flush(); // Return the committed-intent version; constraint/event failures roll back both writes.
            return IncidentResponse.from(incident);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && "incidents_one_ongoing_service_idx".equals(violation.getConstraintName())) {
                    throw new IncidentConflictException("Another ongoing incident already exists for this monitored service");
                }
            }
            throw exception;
        }
    }

    @Transactional
    public IncidentResponse changeAssignee(String subject, UUID organisationId, UUID incidentId,
                                           UUID assigneeUserId, long expectedVersion) {
        if (expectedVersion < 0) throw new InvalidIncidentQueryException("Nonnegative expectedVersion is required");
        OrganisationMembership actor = writer(subject, organisationId);
        Incident incident = lockIncident(organisationId, incidentId);
        if (incident.getVersion() != expectedVersion) throw new IncidentConflictException("Incident changed; reload it before retrying");
        if (!incident.getStatus().isOngoing()) throw new IncidentConflictException("Only ongoing incidents can be assigned");
        UUID actorId = actor.getUser().getId();
        if (actor.getRole() == OrganisationRole.ENGINEER
                && ((assigneeUserId != null && !actorId.equals(assigneeUserId))
                || (incident.getAssignedUserId() != null && !actorId.equals(incident.getAssignedUserId())))) {
            throw new InsufficientOrganisationPermissionException("ENGINEER may only claim an unassigned incident or release their own assignment");
        }
        OrganisationMembership assignee = assigneeUserId == null ? null : membershipRepository
                .findByOrganisationIdAndUserId(organisationId, assigneeUserId)
                .filter(value -> value.getUser().getStatus() == UserStatus.ACTIVE && value.getRole() != OrganisationRole.VIEWER)
                .orElseThrow(IncidentAssigneeNotFoundException::new);
        UUID previous = incident.getAssignedUserId();
        if (!incident.assignTo(assignee)) return IncidentResponse.from(incident);
        try {
            incidents.save(incident);
            events.save(IncidentEvent.assignmentChanged(incident, previous, actor, transitionTime(incident)));
            incidents.flush();
            return IncidentResponse.from(incident);
        } catch (DataIntegrityViolationException exception) {
            throw referenceConflict(exception);
        }
    }

    @Transactional
    public IncidentEventResponse addComment(String subject, UUID organisationId, UUID incidentId,
                                            String content, String idempotencyKey) {
        if (content == null || content.isBlank() || content.length() > IncidentEvent.DETAILS_MAX_LENGTH || content.indexOf('\0') >= 0) {
            throw new InvalidIncidentQueryException("Comment must contain text and be at most 4000 characters");
        }
        UUID requestId = commentRequestId(idempotencyKey);
        OrganisationMembership author = writer(subject, organisationId);
        Incident incident = lockIncident(organisationId, incidentId);
        // Comments are append-only in every lifecycle status and do not mutate the incident/version.
        String text = content.strip();
        if (requestId != null) {
            var existing = events.findByOrganisationIdAndIncidentIdAndActorUserIdAndClientRequestId(
                    organisationId, incidentId, author.getUser().getId(), requestId);
            if (existing.isPresent()) {
                if (!Objects.equals(existing.get().getDetails(), text)) {
                    throw new IncidentConflictException("Idempotency key was already used for a different comment");
                }
                return IncidentEventResponse.from(existing.get());
            }
        }
        try {
            var event = events.save(IncidentEvent.comment(incident, author, transitionTime(incident), text, requestId));
            incidents.flush(); // Validate the append before returning; failure rolls back the transaction.
            return IncidentEventResponse.from(event);
        } catch (DataIntegrityViolationException exception) {
            throw referenceConflict(exception);
        }
    }

    private RuntimeException referenceConflict(DataIntegrityViolationException exception) {
        // Membership/resource removal can race both explicit flushes and query-triggered automatic flushes.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "23503".equals(sql.getSQLState())) {
                return new IncidentConflictException("Incident or membership changed; reload and retry");
            }
        }
        return exception; // Do not disguise unrelated constraints or audit persistence failures as retryable races.
    }

    private UUID commentRequestId(String key) {
        if (key == null) return null;
        try {
            if (key.length() == 36) {
                UUID id = UUID.fromString(key);
                if (id.toString().equalsIgnoreCase(key)) return id;
            }
        } catch (IllegalArgumentException ignored) { /* Return the same safe validation response. */ }
        throw new InvalidIncidentQueryException("Idempotency-Key must be a canonical UUID");
    }

    private OrganisationMembership writer(String subject, UUID organisationId) {
        var member = member(subject, organisationId);
        if (member.getRole() == OrganisationRole.VIEWER) {
            throw new InsufficientOrganisationPermissionException("VIEWER cannot modify incidents or add comments");
        }
        return member;
    }

    private Incident lockIncident(UUID organisationId, UUID incidentId) {
        // Preserve the correlation/lifecycle service-then-incident lock order without stale entity reads.
        var scope = incidents.findScopeByIdAndOrganisationId(incidentId, organisationId).orElseThrow(IncidentNotFoundException::new);
        if (scope.getMonitoredServiceId() != null) services.findByIdAndOrganisationIdForUpdate(scope.getMonitoredServiceId(), organisationId)
                .orElseThrow(IncidentNotFoundException::new);
        return incidents.findByIdAndOrganisationIdForUpdate(incidentId, organisationId).orElseThrow(IncidentNotFoundException::new);
    }

    private Instant transitionTime(Incident incident) {
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (Instant bound : new Instant[]{incident.getCreatedAt(), incident.getAcknowledgedAt(), incident.getResolvedAt()}) {
            if (bound != null && at.isBefore(bound)) at = bound;
        }
        var latest = events.findFirstByOrganisationIdAndIncidentIdOrderByOccurredAtDescIdDesc(incident.getOrganisationId(), incident.getId());
        if (latest.isPresent() && !at.isAfter(latest.get().getOccurredAt())) at = latest.get().getOccurredAt().plus(1, ChronoUnit.MICROS);
        return at;
    }

    private OrganisationMembership member(String subject, UUID organisationId) {
        return memberships.requireMembership(organisationId, authenticatedUsers.findByIdentityProviderSubject(subject).getId());
    }

    private Incident find(UUID organisationId, UUID incidentId) {
        return incidents.findByIdAndOrganisationId(incidentId, organisationId).orElseThrow(IncidentNotFoundException::new);
    }
}
