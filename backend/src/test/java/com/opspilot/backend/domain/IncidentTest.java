package com.opspilot.backend.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IncidentTest {
    static final UUID ORG = UUID.randomUUID();
    static final Instant START = Instant.parse("2026-10-27T12:00:00.123456789Z");

    static Incident incident(UUID organisationId) {
        Incident incident = Incident.open(organisationId, "Availability issue", "Investigate HTTP failures",
                AlertSeverity.HIGH, START, null);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        return incident;
    }

    static OrganisationMembership member(UUID organisationId) {
        Organisation organisation = Organisation.create("Incident fixture", "incident-fixture");
        ReflectionTestUtils.setField(organisation, "id", organisationId);
        User user = User.create("incident-user", "Incident user", "incident@example.test");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return OrganisationMembership.create(organisation, user, OrganisationRole.ENGINEER);
    }

    @ParameterizedTest @EnumSource(AlertSeverity.class)
    void opensWithDefaultStatusAndExplicitSeverity(AlertSeverity severity) {
        Incident incident = Incident.open(ORG, "  Outage  ", "  HTTP failures  ", severity, START, null);
        assertNull(incident.getId());
        assertEquals(ORG, incident.getOrganisationId());
        assertEquals("Outage", incident.getTitle());
        assertEquals("HTTP failures", incident.getDescription());
        assertEquals(severity, incident.getSeverity());
        assertEquals(IncidentStatus.OPEN, incident.getStatus());
        assertEquals(Instant.parse("2026-10-27T12:00:00.123456Z"), incident.getCreatedAt());
        assertNull(incident.getAssignedUserId());
        assertNull(incident.getAcknowledgedAt());
        assertNull(incident.getResolvedAt());
    }

    @Test void acceptsSameOrganisationAssigneeAndRejectsForeignMember() {
        var assignee = member(ORG);
        assertEquals(assignee.getUser().getId(), Incident.open(ORG, "Outage", "Description",
                AlertSeverity.HIGH, START, assignee).getAssignedUserId());
        assertThrows(IllegalArgumentException.class, () -> Incident.open(ORG, "Outage", "Description",
                AlertSeverity.HIGH, START, member(UUID.randomUUID())));
        ReflectionTestUtils.setField(assignee.getUser(), "id", null);
        assertThrows(NullPointerException.class, () -> Incident.open(ORG, "Outage", "Description",
                AlertSeverity.HIGH, START, assignee));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\n\t", "bad\u0000text"})
    void rejectsInvalidTitle(String title) {
        assertThrows(IllegalArgumentException.class, () -> Incident.open(ORG, title, "Description",
                AlertSeverity.HIGH, START, null));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\n\t", "bad\u0000text"})
    void rejectsInvalidDescription(String description) {
        assertThrows(IllegalArgumentException.class, () -> Incident.open(ORG, "Title", description,
                AlertSeverity.HIGH, START, null));
    }

    @Test void validatesTextBoundsAndRequiredValues() {
        assertNotNull(Incident.open(ORG, "t".repeat(200), "d".repeat(4000), AlertSeverity.LOW, START, null));
        assertThrows(IllegalArgumentException.class, () -> Incident.open(ORG, "t".repeat(201), "Description", AlertSeverity.HIGH, START, null));
        assertThrows(IllegalArgumentException.class, () -> Incident.open(ORG, "Title", "d".repeat(4001), AlertSeverity.HIGH, START, null));
        assertThrows(NullPointerException.class, () -> Incident.open(null, "Title", "Description", AlertSeverity.HIGH, START, null));
        assertThrows(NullPointerException.class, () -> Incident.open(ORG, "Title", "Description", null, START, null));
        assertThrows(NullPointerException.class, () -> Incident.open(ORG, "Title", "Description", AlertSeverity.HIGH, null, null));
    }
}
