package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Internal synchronous consumer of persisted alert state; no caller-supplied identity or actor. */
@Service
@Transactional
public class IncidentCorrelationService {
    private static final List<IncidentStatus> ONGOING = Arrays.stream(IncidentStatus.values())
            .filter(IncidentStatus::isOngoing).toList();
    private final MonitoredServiceRepository services;
    private final AlertRepository alerts;
    private final IncidentRepository incidents;
    private final IncidentAlertRepository links;
    private final IncidentEventRepository events;
    private final Clock clock;

    public IncidentCorrelationService(MonitoredServiceRepository services, AlertRepository alerts,
                                       IncidentRepository incidents, IncidentAlertRepository links,
                                       IncidentEventRepository events, Clock clock) {
        this.services = services; this.alerts = alerts; this.incidents = incidents;
        this.links = links; this.events = events; this.clock = clock;
    }

    public void process(UUID organisationId, UUID serviceId, UUID alertId) {
        // Same parent lock and order as monitoring/evaluation, even before an incident exists.
        services.findByIdAndOrganisationIdForUpdate(serviceId, organisationId)
                .orElseThrow(() -> new IllegalArgumentException("Monitored service is unavailable in this organisation"));
        Alert alert = alerts.findByIdAndOrganisationId(alertId, organisationId)
                .filter(value -> organisationId.equals(value.getOrganisationId()) && serviceId.equals(value.getMonitoredServiceId()))
                .orElseThrow(() -> new IllegalArgumentException("Alert is unavailable in this organisation and service"));
        var existing = links.findFirstByOrganisationIdAndAlertIdOrderByIdAsc(organisationId, alertId);
        if (existing.isPresent()) {
            Incident incident = incidents.findByIdAndOrganisationIdForUpdate(existing.get().getIncidentId(), organisationId)
                    .orElseThrow(() -> new IllegalStateException("Incident association is unavailable"));
            if (alert.getStatus() == AlertStatus.RESOLVED) {
                append(incident, alert, IncidentEventType.COMMENT_ADDED,
                        "Alert " + alertId + " resolved at " + alert.getResolvedAt()
                                + "; recovery health-check result " + alert.getResolutionHealthCheckResultId()
                                + ". Incident status is unchanged.");
            } else {
                escalate(incident, alert);
                attached(incident, alert);
            }
            return; // Never reopen/reassign an alert already linked to a finished incident.
        }
        if (alert.getStatus() == AlertStatus.RESOLVED) return; // Recovery is not a reason to open a historical incident.

        Incident incident = incidents.findFirstByOrganisationIdAndMonitoredServiceIdAndStatusInOrderByCreatedAtAscIdAsc(
                organisationId, serviceId, ONGOING).orElse(null);
        boolean created = incident == null;
        if (created) incident = incidents.save(Incident.openForAlert(alert, clock.instant()));
        links.save(IncidentAlert.link(incident, alert));
        escalate(incident, alert);
        if (created) append(incident, alert, IncidentEventType.CREATED,
                "Incident opened automatically for ACTIVE alert " + alertId + ".");
        attached(incident, alert);
    }

    private void escalate(Incident incident, Alert alert) {
        if (incident.getStatus().isOngoing()) {
            incident.raiseSeverityTo(alert.getSeverity());
            incidents.save(incident);
        }
    }

    private void attached(Incident incident, Alert alert) {
        append(incident, alert, IncidentEventType.ALERT_LINKED,
                "ACTIVE alert " + alert.getId() + " attached for monitored service " + alert.getMonitoredServiceId()
                        + "; alert severity " + alert.getSeverity() + "; incident severity " + incident.getSeverity() + ".");
    }

    private void append(Incident incident, Alert alert, IncidentEventType type, String details) {
        var existing = events.findByOrganisationIdAndSourceAlertIdAndEventType(incident.getOrganisationId(), alert.getId(), type);
        if (existing.isPresent()) {
            if (!incident.getId().equals(existing.get().getIncidentId())) {
                throw new IllegalStateException("Alert timeline evidence belongs to a different incident");
            }
            return;
        }
        Instant at = clock.instant();
        if (at.isBefore(incident.getCreatedAt())) at = incident.getCreatedAt();
        events.save(IncidentEvent.forSystemAlert(incident, alert, type, at, details));
    }
}
