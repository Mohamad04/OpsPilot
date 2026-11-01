package com.opspilot.backend.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class IncidentCollaborationTest {
    private final Instant at = Instant.parse("2026-10-28T12:00:00Z");
    private final UUID organisationId = UUID.randomUUID();

    private Incident incident() {
        var incident = Incident.open(organisationId, "Fixture", "Description", AlertSeverity.HIGH, at, null);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        return incident;
    }

    private OrganisationMembership member(UUID org, OrganisationRole role) {
        var organisation = Organisation.create("Fixture", "fixture"); ReflectionTestUtils.setField(organisation, "id", org);
        var user = User.create("subject", "Member", "member@example.test"); ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return OrganisationMembership.create(organisation, user, role);
    }

    @Test void assignmentChangesAreEncapsulatedAndUnchangedValuesAreNoOps() {
        var incident = incident(); var first = member(organisationId, OrganisationRole.ENGINEER);
        var second = member(organisationId, OrganisationRole.ADMIN);
        assertTrue(incident.assignTo(first)); assertFalse(incident.assignTo(first));
        assertTrue(incident.assignTo(second)); assertEquals(second.getUser().getId(), incident.getAssignedUserId());
        var event = IncidentEvent.assignmentChanged(incident, first.getUser().getId(), second, at);
        assertEquals(first.getUser().getId(), event.getPreviousAssigneeUserId());
        assertEquals(second.getUser().getId(), event.getNewAssigneeUserId());
        assertEquals(second.getUser().getId(), event.getActorUserId());
        assertTrue(incident.assignTo(null)); assertFalse(incident.assignTo(null));
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.assignmentChanged(incident, null, first, at));
        assertThrows(NullPointerException.class, () -> IncidentEvent.assignmentChanged(incident, UUID.randomUUID(), null, at));
    }

    @Test void invalidAssigneesAndFinishedIncidentAssignmentAreRejected() {
        var incident = incident();
        assertThrows(IllegalArgumentException.class, () -> incident.assignTo(member(UUID.randomUUID(), OrganisationRole.ENGINEER)));
        assertThrows(IllegalArgumentException.class, () -> incident.assignTo(member(organisationId, OrganisationRole.VIEWER)));
        var inactive = member(organisationId, OrganisationRole.ADMIN);
        ReflectionTestUtils.setField(inactive.getUser(), "status", UserStatus.INACTIVE);
        assertThrows(IllegalArgumentException.class, () -> incident.assignTo(inactive));
        incident.changeStatus(IncidentStatus.ACKNOWLEDGED, at); incident.changeStatus(IncidentStatus.RESOLVED, at);
        assertThrows(IllegalStateException.class, () -> incident.assignTo(null));
    }

    @Test void commentsAreHumanAttributedPlainTextAndDoNotChangeIncidentVersionOrStatus() {
        var incident = incident(); var author = member(organisationId, OrganisationRole.ENGINEER); var key = UUID.randomUUID();
        var event = IncidentEvent.comment(incident, author, at, " <script>untrusted()</script> ", key);
        assertEquals("<script>untrusted()</script>", event.getDetails());
        assertEquals(IncidentEventType.COMMENT_ADDED, event.getEventType()); assertNull(event.getSourceAlertId());
        assertEquals(author.getUser().getId(), event.getActorUserId()); assertEquals(key, event.getClientRequestId());
        assertEquals(0, incident.getVersion()); assertEquals(IncidentStatus.OPEN, incident.getStatus());
        assertThrows(NullPointerException.class, () -> IncidentEvent.comment(incident, null, at, "Text", key));
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.comment(incident, member(UUID.randomUUID(), OrganisationRole.ENGINEER), at, "Text", key));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\n\t", "invalid\0text"})
    void invalidCommentTextIsRejectedInTheDomain(String content) {
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.comment(incident(), member(organisationId, OrganisationRole.ENGINEER), at, content, null));
    }

    @Test void commentLengthIsBounded() {
        var incident = incident(); var author = member(organisationId, OrganisationRole.ENGINEER);
        assertEquals(4000, IncidentEvent.comment(incident, author, at, "x".repeat(4000), null).getDetails().length());
        assertThrows(IllegalArgumentException.class, () -> IncidentEvent.comment(incident, author, at, "x".repeat(4001), null));
    }
}
