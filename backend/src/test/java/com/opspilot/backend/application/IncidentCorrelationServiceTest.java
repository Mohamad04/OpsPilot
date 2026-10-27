package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentCorrelationServiceTest {
    @Mock MonitoredServiceRepository services;
    @Mock AlertRepository alerts;
    @Mock IncidentRepository incidents;
    @Mock IncidentAlertRepository links;
    @Mock IncidentEventRepository events;
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private final UUID organisationId = UUID.randomUUID(), serviceId = UUID.randomUUID();

    private IncidentCorrelationService consumer() {
        return new IncidentCorrelationService(services, alerts, incidents, links, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Alert givenAlert() {
        var organisation = Organisation.create("Correlation unit", "correlation-unit");
        ReflectionTestUtils.setField(organisation, "id", organisationId);
        var service = MonitoredService.create(organisation, "Fixture", ServiceType.HTTP, Environment.TEST,
                "https://fixture.example.test", "/health", "Unit test");
        ReflectionTestUtils.setField(service, "id", serviceId);
        when(services.findByIdAndOrganisationIdForUpdate(serviceId, organisationId)).thenReturn(Optional.of(service));
        var down = new HealthCheckResult(organisationId, serviceId, NOW, NOW, HealthStatus.DOWN, 503, 1, "Failure");
        ReflectionTestUtils.setField(down, "id", UUID.randomUUID());
        var alert = Alert.open(down, down.getId(), NOW, 3, 3, 2, AlertSeverity.HIGH);
        ReflectionTestUtils.setField(alert, "id", UUID.randomUUID());
        when(alerts.findByIdAndOrganisationId(alert.getId(), organisationId)).thenReturn(Optional.of(alert));
        return alert;
    }

    private Incident givenLinkedIncident(Alert alert) {
        var incident = Incident.openForAlert(alert, NOW);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        when(links.findFirstByOrganisationIdAndAlertIdOrderByIdAsc(organisationId, alert.getId()))
                .thenReturn(Optional.of(IncidentAlert.link(incident, alert)));
        when(incidents.findByIdAndOrganisationIdForUpdate(incident.getId(), organisationId)).thenReturn(Optional.of(incident));
        return incident;
    }

    @Test void newActiveAlertCreatesAnUnassignedOpenIncidentAndTwoSystemEvents() {
        var alert = givenAlert();
        when(incidents.save(any())).thenAnswer(call -> {
            Incident incident = call.getArgument(0);
            if (incident.getId() == null) ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
            return incident;
        });
        consumer().process(organisationId, serviceId, alert.getId());
        var incidentCapture = ArgumentCaptor.forClass(Incident.class);
        verify(incidents, atLeastOnce()).save(incidentCapture.capture());
        var incident = incidentCapture.getValue();
        assertEquals(IncidentStatus.OPEN, incident.getStatus()); assertNull(incident.getAssignedUserId());
        assertEquals(serviceId, incident.getMonitoredServiceId()); assertEquals(AlertSeverity.HIGH, incident.getSeverity());
        var eventCapture = ArgumentCaptor.forClass(IncidentEvent.class);
        verify(events, times(2)).save(eventCapture.capture());
        assertEquals(IncidentEventType.CREATED, eventCapture.getAllValues().get(0).getEventType());
        assertEquals(IncidentEventType.ALERT_LINKED, eventCapture.getAllValues().get(1).getEventType());
        for (var event : eventCapture.getAllValues()) {
            assertNull(event.getActorUserId()); assertEquals(alert.getId(), event.getSourceAlertId());
            assertEquals(organisationId, event.getOrganisationId()); assertEquals(NOW, event.getOccurredAt());
        }
        verify(links).save(any());
        verify(alerts, never()).findById(any());
    }

    @Test void replayUsesExistingAssociationWithoutDuplicatingItsTimeline() {
        var alert = givenAlert(); var incident = givenLinkedIncident(alert);
        var linked = IncidentEvent.forSystemAlert(incident, alert, IncidentEventType.ALERT_LINKED, NOW, "Original attachment");
        when(events.findByOrganisationIdAndSourceAlertIdAndEventType(organisationId, alert.getId(), IncidentEventType.ALERT_LINKED))
                .thenReturn(Optional.of(linked));
        consumer().process(organisationId, serviceId, alert.getId());
        verify(links, never()).save(any()); verify(events, never()).save(any());
        assertEquals(IncidentStatus.OPEN, incident.getStatus());
    }

    @Test void recoveryAddsEvidenceWithoutResolvingIncidentAndHandlesClockGoingBackwards() {
        var alert = givenAlert(); var incident = givenLinkedIncident(alert);
        ReflectionTestUtils.setField(incident, "createdAt", NOW.plusSeconds(10));
        var up = new HealthCheckResult(organisationId, serviceId, NOW.plusSeconds(1), NOW.plusSeconds(1), HealthStatus.UP, 200, 2, null);
        ReflectionTestUtils.setField(up, "id", UUID.randomUUID()); alert.resolve(up, 2);
        consumer().process(organisationId, serviceId, alert.getId());
        var capture = ArgumentCaptor.forClass(IncidentEvent.class); verify(events).save(capture.capture());
        assertEquals(IncidentEventType.COMMENT_ADDED, capture.getValue().getEventType());
        assertNull(capture.getValue().getActorUserId());
        assertTrue(capture.getValue().getDetails().contains(up.getId().toString()));
        assertEquals(incident.getCreatedAt(), capture.getValue().getOccurredAt());
        assertEquals(IncidentStatus.OPEN, incident.getStatus()); assertNull(incident.getResolvedAt());
        verify(incidents, never()).save(any()); verify(links, never()).save(any());
    }

    @Test void historicalResolvedAlertWithNoAssociationDoesNotCreateIncident() {
        var alert = givenAlert();
        var up = new HealthCheckResult(organisationId, serviceId, NOW.plusSeconds(1), NOW.plusSeconds(1), HealthStatus.UP, 200, 1, null);
        ReflectionTestUtils.setField(up, "id", UUID.randomUUID()); alert.resolve(up, 2);
        consumer().process(organisationId, serviceId, alert.getId());
        verifyNoInteractions(incidents, events); verify(links, never()).save(any());
    }

    @Test void rejectsMissingOrForeignIdentityBeforeAnyIncidentAccess() {
        assertThrows(IllegalArgumentException.class, () -> consumer().process(organisationId, serviceId, UUID.randomUUID()));
        verifyNoInteractions(alerts, incidents, links, events);
        var alert = givenAlert();
        ReflectionTestUtils.setField(alert, "monitoredServiceId", UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> consumer().process(organisationId, serviceId, alert.getId()));
        verifyNoInteractions(incidents, links, events);
    }

    @Test void inconsistentTimelineAssociationIsRejectedRatherThanRewritten() {
        var alert = givenAlert(); givenLinkedIncident(alert);
        var other = Incident.openForAlert(alert, NOW); ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        when(events.findByOrganisationIdAndSourceAlertIdAndEventType(organisationId, alert.getId(), IncidentEventType.ALERT_LINKED))
                .thenReturn(Optional.of(IncidentEvent.forSystemAlert(other, alert, IncidentEventType.ALERT_LINKED, NOW, "Different association")));
        assertThrows(IllegalStateException.class, () -> consumer().process(organisationId, serviceId, alert.getId()));
        verify(events, never()).save(any()); verify(links, never()).save(any());
    }
}
