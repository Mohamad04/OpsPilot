package com.opspilot.backend.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "incident_events")
public class IncidentEvent {
    public static final int DETAILS_MAX_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, updatable = false)
    private UUID organisationId;
    @Column(nullable = false, updatable = false)
    private UUID incidentId;
    @Column(updatable = false)
    private UUID sourceAlertId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private IncidentEventType eventType;
    @Column(updatable = false)
    private UUID actorUserId;
    @Column(nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(nullable = false, updatable = false, length = DETAILS_MAX_LENGTH)
    private String details;

    protected IncidentEvent() { }

    /** Details are bounded plain-text audit context, never executable content or an opaque JSON contract. */
    public static IncidentEvent record(Incident incident, IncidentEventType eventType,
                                       OrganisationMembership actor, Instant occurredAt, String details) {
        Objects.requireNonNull(incident, "Incident is required");
        IncidentEvent event = new IncidentEvent();
        event.organisationId = Objects.requireNonNull(incident.getOrganisationId());
        event.incidentId = Objects.requireNonNull(incident.getId(), "Persisted incident is required");
        event.eventType = Objects.requireNonNull(eventType, "Event type is required");
        event.actorUserId = Incident.memberUserId(event.organisationId, actor);
        event.occurredAt = Objects.requireNonNull(occurredAt, "Event time is required").truncatedTo(ChronoUnit.MICROS);
        if (event.occurredAt.isBefore(incident.getCreatedAt())) {
            throw new IllegalArgumentException("Event cannot precede incident creation");
        }
        if (details == null || details.isBlank() || details.length() > DETAILS_MAX_LENGTH || details.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Event details must contain text and be at most " + DETAILS_MAX_LENGTH + " characters");
        }
        event.details = details.strip();
        return event;
    }

    @PreRemove
    private void rejectRemoval() {
        throw new IllegalStateException("Incident events are append-only");
    }

    /** System correlation evidence: COMMENT_ADDED is a recovery note, not a human comment. */
    public static IncidentEvent forSystemAlert(Incident incident, Alert alert, IncidentEventType type,
                                               Instant occurredAt, String details) {
        Objects.requireNonNull(alert, "Alert is required");
        IncidentAlert.link(incident, alert); // Apply the same persisted identity/tenant/service invariants.
        boolean recovery = type == IncidentEventType.COMMENT_ADDED;
        if (type != IncidentEventType.CREATED && type != IncidentEventType.ALERT_LINKED && !recovery) {
            throw new IllegalArgumentException("Unsupported system alert event type");
        }
        if (alert.getStatus() != (recovery ? AlertStatus.RESOLVED : AlertStatus.ACTIVE)) {
            throw new IllegalArgumentException("System event must match the persisted alert lifecycle");
        }
        IncidentEvent event = record(incident, type, null, occurredAt, details);
        event.sourceAlertId = alert.getId();
        return event;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getIncidentId() { return incidentId; }
    public UUID getSourceAlertId() { return sourceAlertId; }
    public IncidentEventType getEventType() { return eventType; }
    public UUID getActorUserId() { return actorUserId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getDetails() { return details; }
}
