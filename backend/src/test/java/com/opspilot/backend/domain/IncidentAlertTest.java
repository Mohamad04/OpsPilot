package com.opspilot.backend.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IncidentAlertTest {
    private Alert alert(UUID organisationId) {
        HealthCheckResult result = new HealthCheckResult(organisationId, UUID.randomUUID(), IncidentTest.START,
                IncidentTest.START.plusMillis(4), HealthStatus.DOWN, 503, 4, "Unexpected HTTP status: 503.");
        ReflectionTestUtils.setField(result, "id", UUID.randomUUID());
        Alert alert = Alert.open(result, result.getId(), result.getStartedAt(), 3, 3, 2, AlertSeverity.HIGH);
        ReflectionTestUtils.setField(alert, "id", UUID.randomUUID());
        return alert;
    }

    @Test void linksPersistedIncidentAndAlertWithinOneOrganisation() {
        var incident = IncidentTest.incident(IncidentTest.ORG);
        var alert = alert(IncidentTest.ORG);
        var link = IncidentAlert.link(incident, alert);
        assertNull(link.getId());
        assertEquals(IncidentTest.ORG, link.getOrganisationId());
        assertEquals(incident.getId(), link.getIncidentId());
        assertEquals(alert.getId(), link.getAlertId());
        assertEquals(AlertStatus.ACTIVE, alert.getStatus());
        assertEquals(3, alert.getOccurrenceCount());
    }

    @Test void rejectsForeignAndUnpersistedAssociations() {
        var incident = IncidentTest.incident(IncidentTest.ORG);
        var alert = alert(IncidentTest.ORG);
        assertThrows(IllegalArgumentException.class, () -> IncidentAlert.link(incident, alert(UUID.randomUUID())));
        ReflectionTestUtils.setField(alert, "id", null);
        assertThrows(NullPointerException.class, () -> IncidentAlert.link(incident, alert));
        ReflectionTestUtils.setField(incident, "id", null);
        assertThrows(NullPointerException.class, () -> IncidentAlert.link(incident, alert(IncidentTest.ORG)));
        assertThrows(NullPointerException.class, () -> IncidentAlert.link(null, alert));
        assertThrows(NullPointerException.class, () -> IncidentAlert.link(incident, null));
    }
}
