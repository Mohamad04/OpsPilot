package com.opspilot.backend.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IncidentCorrelationDomainTest {
    private Alert active(AlertSeverity severity) {
        var result = new HealthCheckResult(IncidentTest.ORG, UUID.randomUUID(), IncidentTest.START,
                IncidentTest.START, HealthStatus.DOWN, 503, 1, "Failure");
        ReflectionTestUtils.setField(result, "id", UUID.randomUUID());
        var alert = Alert.open(result, result.getId(), result.getStartedAt(), 3, 3, 2, severity);
        ReflectionTestUtils.setField(alert, "id", UUID.randomUUID());
        return alert;
    }

    @ParameterizedTest @EnumSource(AlertSeverity.class)
    void incidentStartsAtAlertSeverityAndNeverDowngrades(AlertSeverity severity) {
        var alert = active(severity); var incident = Incident.openForAlert(alert, IncidentTest.START);
        assertEquals(alert.getMonitoredServiceId(), incident.getMonitoredServiceId());
        assertEquals(severity, incident.getSeverity()); assertEquals(IncidentStatus.OPEN, incident.getStatus());
        incident.raiseSeverityTo(AlertSeverity.LOW); assertEquals(severity, incident.getSeverity());
        incident.raiseSeverityTo(AlertSeverity.CRITICAL); assertEquals(AlertSeverity.CRITICAL, incident.getSeverity());
        incident.raiseSeverityTo(AlertSeverity.HIGH); assertEquals(AlertSeverity.CRITICAL, incident.getSeverity());
    }

    @ParameterizedTest @EnumSource(IncidentStatus.class)
    void ongoingClassificationAndFinishedIncidentEscalationAreExplicit(IncidentStatus status) {
        var incident = Incident.openForAlert(active(AlertSeverity.LOW), IncidentTest.START);
        ReflectionTestUtils.setField(incident, "status", status);
        if (status == IncidentStatus.RESOLVED || status == IncidentStatus.CLOSED) {
            assertFalse(status.isOngoing());
            assertThrows(IllegalStateException.class, () -> incident.raiseSeverityTo(AlertSeverity.HIGH));
        } else {
            assertTrue(status.isOngoing()); incident.raiseSeverityTo(AlertSeverity.HIGH);
            assertEquals(AlertSeverity.HIGH, incident.getSeverity());
        }
    }

    @Test void systemEventsRequirePersistedMatchingScopeAndAlertLifecycle() {
        var alert = active(AlertSeverity.HIGH); var incident = Incident.openForAlert(alert, IncidentTest.START);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        var event = IncidentEvent.forSystemAlert(incident, alert, IncidentEventType.ALERT_LINKED, IncidentTest.START, "Attached");
        assertEquals(alert.getId(), event.getSourceAlertId()); assertNull(event.getActorUserId());
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.forSystemAlert(incident, alert,
                IncidentEventType.COMMENT_ADDED, IncidentTest.START, "Not recovered"));
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.forSystemAlert(incident, alert,
                IncidentEventType.STATUS_CHANGED, IncidentTest.START, "Not an incident transition"));
        var sibling = active(AlertSeverity.LOW);
        assertThrows(IllegalArgumentException.class, () -> IncidentAlert.link(incident, sibling));
        ReflectionTestUtils.setField(alert, "organisationId", UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.forSystemAlert(incident, alert,
                IncidentEventType.CREATED, IncidentTest.START, "Foreign"));
    }

    @Test void onlyPersistedActiveAlertsCanOpenAnIncident() {
        var alert = active(AlertSeverity.HIGH);
        ReflectionTestUtils.setField(alert, "status", AlertStatus.RESOLVED);
        assertThrows(IllegalArgumentException.class, () -> Incident.openForAlert(alert, IncidentTest.START));
        ReflectionTestUtils.setField(alert, "id", null);
        assertThrows(NullPointerException.class, () -> Incident.openForAlert(alert, IncidentTest.START));
    }
}
