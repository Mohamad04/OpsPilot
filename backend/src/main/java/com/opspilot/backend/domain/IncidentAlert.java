package com.opspilot.backend.domain;

import jakarta.persistence.*;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "incident_alerts")
public class IncidentAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, updatable = false)
    private UUID organisationId;
    @Column(nullable = false, updatable = false)
    private UUID incidentId;
    @Column(nullable = false, updatable = false)
    private UUID alertId;

    protected IncidentAlert() { }

    public static IncidentAlert link(Incident incident, Alert alert) {
        Objects.requireNonNull(incident, "Incident is required");
        Objects.requireNonNull(alert, "Alert is required");
        if (!Objects.requireNonNull(incident.getOrganisationId()).equals(alert.getOrganisationId())) {
            throw new IllegalArgumentException("Incident and alert must belong to the same organisation");
        }
        IncidentAlert link = new IncidentAlert();
        link.organisationId = incident.getOrganisationId();
        link.incidentId = Objects.requireNonNull(incident.getId(), "Persisted incident is required");
        link.alertId = Objects.requireNonNull(alert.getId(), "Persisted alert is required");
        return link;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getIncidentId() { return incidentId; }
    public UUID getAlertId() { return alertId; }
}
