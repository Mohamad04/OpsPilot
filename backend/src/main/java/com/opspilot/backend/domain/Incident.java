package com.opspilot.backend.domain;

import jakarta.persistence.*;

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
    @Column(nullable = false, updatable = false)
    private UUID organisationId;
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

    /** Opens an incident only; lifecycle transitions belong to the next domain increment. */
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
    public UUID getOrganisationId() { return organisationId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public AlertSeverity getSeverity() { return severity; }
    public IncidentStatus getStatus() { return status; }
    public UUID getAssignedUserId() { return assignedUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
