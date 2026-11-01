package com.opspilot.backend.domain;

import com.opspilot.backend.domain.exception.InvalidIncidentTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class IncidentLifecycleTest {
    private static final Instant START = Instant.parse("2026-10-27T12:00:00Z");

    static Stream<org.junit.jupiter.params.provider.Arguments> transitions() {
        return Arrays.stream(IncidentStatus.values()).flatMap(from -> Arrays.stream(IncidentStatus.values())
                .map(to -> org.junit.jupiter.params.provider.Arguments.of(from, to)));
    }

    private boolean allowed(IncidentStatus from, IncidentStatus to) {
        return switch (from) {
            case OPEN -> to == IncidentStatus.ACKNOWLEDGED;
            case ACKNOWLEDGED -> to == IncidentStatus.INVESTIGATING || to == IncidentStatus.RESOLVED;
            case INVESTIGATING -> to == IncidentStatus.MITIGATED || to == IncidentStatus.RESOLVED;
            case MITIGATED -> to == IncidentStatus.INVESTIGATING || to == IncidentStatus.RESOLVED;
            case RESOLVED -> to == IncidentStatus.CLOSED || to == IncidentStatus.INVESTIGATING;
            case CLOSED -> to == IncidentStatus.INVESTIGATING;
        };
    }

    @ParameterizedTest @MethodSource("transitions")
    void everyAllowedRejectedAndRepeatedTransitionIsExplicit(IncidentStatus from, IncidentStatus to) {
        Incident incident = Incident.open(IncidentTest.ORG, "Incident", "Description", AlertSeverity.HIGH, START, null);
        ReflectionTestUtils.setField(incident, "status", from);
        if (from != IncidentStatus.OPEN) ReflectionTestUtils.setField(incident, "acknowledgedAt", START.plusSeconds(1));
        if (!from.isOngoing()) ReflectionTestUtils.setField(incident, "resolvedAt", START.plusSeconds(2));
        assertEquals(allowed(from, to), from.canTransitionTo(to));
        if (allowed(from, to)) {
            incident.changeStatus(to, START.plusSeconds(3)); assertEquals(to, incident.getStatus());
            if (to == IncidentStatus.ACKNOWLEDGED) assertEquals(START.plusSeconds(3), incident.getAcknowledgedAt());
            if (to == IncidentStatus.RESOLVED) assertEquals(START.plusSeconds(3), incident.getResolvedAt());
            if (!from.isOngoing() && to.isOngoing()) {
                assertNull(incident.getResolvedAt()); assertEquals(START.plusSeconds(1), incident.getAcknowledgedAt());
            }
        } else {
            assertThrows(InvalidIncidentTransitionException.class, () -> incident.changeStatus(to, START.plusSeconds(3)));
            assertEquals(from, incident.getStatus());
        }
    }

    @Test void acknowledgementSurvivesReopeningAndResolutionHistoryIsAvailableToTheAuditFactory() {
        var incident = IncidentTest.incident(IncidentTest.ORG);
        var at = incident.getCreatedAt().plusSeconds(1);
        incident.changeStatus(IncidentStatus.ACKNOWLEDGED, at);
        incident.changeStatus(IncidentStatus.RESOLVED, at.plusSeconds(1));
        var oldResolution = incident.getResolvedAt();
        incident.changeStatus(IncidentStatus.INVESTIGATING, at.plusSeconds(2));
        var event = IncidentEvent.statusChanged(incident, IncidentStatus.RESOLVED, IncidentTest.member(IncidentTest.ORG), at.plusSeconds(2), oldResolution);
        assertEquals(at, incident.getAcknowledgedAt()); assertNull(incident.getResolvedAt());
        assertEquals(IncidentStatus.RESOLVED, event.getPreviousStatus()); assertEquals(IncidentStatus.INVESTIGATING, event.getNewStatus());
        assertNotNull(event.getActorUserId()); assertNull(event.getSourceAlertId());
        assertTrue(event.getDetails().contains(oldResolution.toString()));
        incident.changeStatus(IncidentStatus.RESOLVED, at.plusSeconds(3));
        assertEquals(at.plusSeconds(3), incident.getResolvedAt());
    }

    @Test void invalidTimeAndMissingActorCannotCreateTransitionsOrAuditEvidence() {
        var incident = IncidentTest.incident(IncidentTest.ORG);
        assertThrows(IllegalArgumentException.class, () -> incident.changeStatus(IncidentStatus.ACKNOWLEDGED, START.minusSeconds(1)));
        assertEquals(IncidentStatus.OPEN, incident.getStatus());
        assertThrows(InvalidIncidentTransitionException.class, () -> incident.changeStatus(null, START));
        incident.changeStatus(IncidentStatus.ACKNOWLEDGED, START.plusSeconds(1));
        assertThrows(NullPointerException.class, () -> IncidentEvent.statusChanged(incident, IncidentStatus.OPEN, null, START.plusSeconds(1), null));
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.statusChanged(incident, IncidentStatus.CLOSED,
                IncidentTest.member(IncidentTest.ORG), START.plusSeconds(1), null));
    }
}
