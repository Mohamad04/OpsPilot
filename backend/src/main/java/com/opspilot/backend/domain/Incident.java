package com.opspilot.backend.domain;

import jakarta.persistence.*;
import com.opspilot.backend.domain.exception.InvalidIncidentTransitionException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "incidents")
public class Incident {
    public static final int TITLE_MAX_LENGTH = 200;
    public static final int DESCRIPTION_MAX_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(nullable = false, updatable = false)
    private UUID organisationId;
    @Column(updatable = false)
    private UUID monitoredServiceId;
    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;
    @Column(nullable = false, length = DESCRIPTION_MAX_LENGTH)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private AlertSeverity severity;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentStatus status;
    private UUID assignedUserId;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    private Instant acknowledgedAt;
    private Instant resolvedAt;

    protected Incident() { }

    /** Creates the initial OPEN state; changeStatus validates subsequent lifecycle transitions. */
    public static Incident open(UUID organisationId, String title, String description,
                                AlertSeverity severity, Instant createdAt,
                                OrganisationMembership assignee) {
        Incident incident = new Incident();
        incident.organisationId = Objects.requireNonNull(organisationId, "Organisation is required");
        incident.title = requireText(title, TITLE_MAX_LENGTH, "Title");
        incident.description = requireText(description, DESCRIPTION_MAX_LENGTH, "Description");
        incident.severity = Objects.requireNonNull(severity, "Severity is required");
        incident.createdAt = Objects.requireNonNull(createdAt, "Creation time is required")
                .truncatedTo(ChronoUnit.MICROS);
        incident.assignedUserId = memberUserId(organisationId, assignee);
        incident.status = IncidentStatus.OPEN;
        return incident;
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must contain text and be at most " + maxLength + " characters");
        }
        return value.strip();
    }

    public static Incident openForAlert(Alert alert, Instant createdAt) {
        Objects.requireNonNull(alert, "Alert is required");
        Objects.requireNonNull(alert.getId(), "Persisted alert is required");
        if (alert.getStatus() != AlertStatus.ACTIVE) {
            throw new IllegalArgumentException("Only an ACTIVE alert can open an incident");
        }
        Incident incident = open(alert.getOrganisationId(), "HTTP availability incident",
                "HTTP availability alert for monitored service " + alert.getMonitoredServiceId() + ".",
                alert.getSeverity(), createdAt, null);
        incident.monitoredServiceId = Objects.requireNonNull(alert.getMonitoredServiceId());
        return incident;
    }

    /** Correlation may increase severity, but never reopen an incident or downgrade it. */
    public void raiseSeverityTo(AlertSeverity incoming) {
        Objects.requireNonNull(incoming, "Severity is required");
        if (!status.isOngoing()) throw new IllegalStateException("A finished incident cannot be escalated");
        if (severityRank(incoming) > severityRank(severity)) severity = incoming;
    }

    private static int severityRank(AlertSeverity value) {
        return switch (value) { case LOW -> 1; case MEDIUM -> 2; case HIGH -> 3; case CRITICAL -> 4; };
    }

    public void changeStatus(IncidentStatus target, Instant at) {
        if (!status.canTransitionTo(target)) throw new InvalidIncidentTransitionException(status, target);
        Instant changedAt = Objects.requireNonNull(at, "Transition time is required").truncatedTo(ChronoUnit.MICROS);
        if (changedAt.isBefore(createdAt) || (acknowledgedAt != null && changedAt.isBefore(acknowledgedAt))
                || (resolvedAt != null && changedAt.isBefore(resolvedAt))) {
            throw new IllegalArgumentException("Transition time cannot precede existing incident timestamps");
        }
        if (target == IncidentStatus.ACKNOWLEDGED) acknowledgedAt = changedAt;
        if (target == IncidentStatus.RESOLVED) resolvedAt = changedAt;
        // Reopening retains the first acknowledgement; prior resolutions remain in immutable events.
        if (!status.isOngoing() && target.isOngoing()) resolvedAt = null;
        status = target;
    }

    /** Assignment is operational work; finished incidents retain their final assignment. */
    public boolean assignTo(OrganisationMembership assignee) {
        if (!status.isOngoing()) throw new IllegalStateException("Only ongoing incidents can be assigned");
        UUID next = memberUserId(organisationId, assignee);
        if (assignee != null && (assignee.getUser().getStatus() != UserStatus.ACTIVE
                || assignee.getRole() == OrganisationRole.VIEWER)) {
            throw new IllegalArgumentException("Assignee must be an active operational member");
        }
        if (Objects.equals(assignedUserId, next)) return false;
        assignedUserId = next;
        return true;
    }

    // Membership is a domain input, not a grant of permission. The database checks current membership too.
    static UUID memberUserId(UUID organisationId, OrganisationMembership membership) {
        if (membership == null) return null;
        if (membership.getOrganisation() == null
                || !organisationId.equals(membership.getOrganisation().getId())) {
            throw new IllegalArgumentException("The user must belong to the incident's organisation");
        }
        Objects.requireNonNull(membership.getUser(), "Member user is required");
        return Objects.requireNonNull(membership.getUser().getId(), "Persisted member user is required");
    }

    public UUID getId() { return id; }
    public long getVersion() { return version; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getMonitoredServiceId() { return monitoredServiceId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public AlertSeverity getSeverity() { return severity; }
    public IncidentStatus getStatus() { return status; }
    public UUID getAssignedUserId() { return assignedUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
