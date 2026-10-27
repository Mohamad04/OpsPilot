package com.opspilot.backend.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IncidentEventTest {
    @ParameterizedTest @EnumSource(IncidentEventType.class)
    void recordsBoundedTextAndAnOptionalActor(IncidentEventType type) {
        var incident = IncidentTest.incident(IncidentTest.ORG);
        var event = IncidentEvent.record(incident, type, null, IncidentTest.START, "  Audit context  ");
        assertNull(event.getId());
        assertEquals(incident.getId(), event.getIncidentId());
        assertEquals(incident.getOrganisationId(), event.getOrganisationId());
        assertEquals(type, event.getEventType());
        assertEquals(incident.getCreatedAt(), event.getOccurredAt());
        assertEquals("Audit context", event.getDetails());
        assertNull(event.getActorUserId());
        var actor = IncidentTest.member(IncidentTest.ORG);
        assertEquals(actor.getUser().getId(), IncidentEvent.record(incident, type, actor,
                IncidentTest.START, "d".repeat(4000)).getActorUserId());
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\n\t", "bad\u0000text"})
    void rejectsMissingOrInvalidDetails(String details) {
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.record(
                IncidentTest.incident(IncidentTest.ORG), IncidentEventType.CREATED, null, IncidentTest.START, details));
    }

    @Test void rejectsInvalidTimeTypeActorAndUnpersistedIncident() {
        var incident = IncidentTest.incident(IncidentTest.ORG);
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.record(incident, IncidentEventType.CREATED,
                null, IncidentTest.START.minusSeconds(1), "Context"));
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.record(incident, IncidentEventType.CREATED,
                IncidentTest.member(UUID.randomUUID()), IncidentTest.START, "Context"));
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.record(incident, IncidentEventType.CREATED,
                null, IncidentTest.START, "d".repeat(4001)));
        assertThrows(NullPointerException.class, () -> IncidentEvent.record(incident, null, null, IncidentTest.START, "Context"));
        assertThrows(NullPointerException.class, () -> IncidentEvent.record(incident, IncidentEventType.CREATED, null, null, "Context"));
        assertThrows(NullPointerException.class, () -> IncidentEvent.record(null, IncidentEventType.CREATED, null, IncidentTest.START, "Context"));
        ReflectionTestUtils.setField(incident, "id", null);
        assertThrows(NullPointerException.class, () -> IncidentEvent.record(incident, IncidentEventType.CREATED, null, IncidentTest.START, "Context"));
    }
}
