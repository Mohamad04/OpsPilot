package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.*;
import com.opspilot.backend.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class IncidentServiceTest {
    @Mock IncidentRepository incidents;
    @Mock IncidentEventRepository events;
    @Mock IncidentAlertRepository links;
    @Mock MonitoredServiceRepository services;
    @Mock AuthenticatedUserService users;
    @Mock OrganisationMembershipService memberships;
    @Mock OrganisationMembershipRepository membershipRepository;
    private final UUID organisationId = UUID.randomUUID(), userId = UUID.randomUUID(), incidentId = UUID.randomUUID();
    private IncidentService application;

    @BeforeEach void setup() {
        application = new IncidentService(incidents, events, links, services, users, memberships, membershipRepository,
                Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC));
    }

    private void member(OrganisationRole role) {
        var user = User.create("subject", "User", "user@example.test"); ReflectionTestUtils.setField(user, "id", userId);
        var organisation = Organisation.create("Unit incident", "unit-incident"); ReflectionTestUtils.setField(organisation, "id", organisationId);
        when(users.findByIdentityProviderSubject("subject")).thenReturn(user);
        when(memberships.requireMembership(organisationId, userId)).thenReturn(OrganisationMembership.create(organisation, user, role));
    }

    @Test void nonMemberFailsBeforeAnyIncidentOrTimelineQuery() {
        when(users.findByIdentityProviderSubject("subject")).thenThrow(new UserNotFoundException("Missing user"));
        assertThrows(UserNotFoundException.class, () -> application.details("subject", organisationId, incidentId));
        verifyNoInteractions(incidents, events, links, services, memberships);
    }

    @Test void viewerCannotMutateEvenWithNonexistentIncident() {
        member(OrganisationRole.VIEWER);
        assertThrows(InsufficientOrganisationPermissionException.class, () -> application.changeStatus("subject", organisationId, incidentId, IncidentStatus.ACKNOWLEDGED, 0));
        verifyNoInteractions(incidents, events, links, services);
    }

    @Test void missingIncidentOrServiceFilterUsesScopedLookups() {
        member(OrganisationRole.ENGINEER);
        assertThrows(IncidentNotFoundException.class, () -> application.details("subject", organisationId, incidentId));
        assertThrows(MonitoredServiceNotFoundException.class, () -> application.list("subject", organisationId,
                new IncidentQuery(null, null, UUID.randomUUID(), 0, 20, "createdAt,desc")));
        verify(incidents).findByIdAndOrganisationId(incidentId, organisationId); verifyNoInteractions(events, links);
    }

    @Test void staleVersionIsRejectedBeforeWrites() {
        member(OrganisationRole.ADMIN);
        var scope = mock(IncidentRepository.Scope.class);
        when(incidents.findScopeByIdAndOrganisationId(incidentId, organisationId)).thenReturn(Optional.of(scope));
        var incident = Incident.open(organisationId, "Title", "Description", AlertSeverity.HIGH, Instant.parse("2026-10-27T12:00:00Z"), null);
        when(incidents.findByIdAndOrganisationIdForUpdate(incidentId, organisationId)).thenReturn(Optional.of(incident));
        assertThrows(IncidentConflictException.class, () -> application.changeStatus("subject", organisationId, incidentId, IncidentStatus.ACKNOWLEDGED, 1));
        verify(incidents, never()).save(any()); verifyNoInteractions(events, links, services);
    }

    @Test void membershipConflictDuringAutomaticFlushReturnsAConflictInsteadOfAnInternalError() {
        member(OrganisationRole.OWNER);
        var actor = memberships.requireMembership(organisationId, userId);
        when(membershipRepository.findByOrganisationIdAndUserId(organisationId, userId)).thenReturn(Optional.of(actor));
        when(incidents.findScopeByIdAndOrganisationId(incidentId, organisationId)).thenReturn(Optional.of(mock(IncidentRepository.Scope.class)));
        var incident = Incident.open(organisationId, "Title", "Description", AlertSeverity.HIGH, Instant.parse("2026-10-27T12:00:00Z"), null);
        ReflectionTestUtils.setField(incident, "id", incidentId);
        when(incidents.findByIdAndOrganisationIdForUpdate(incidentId, organisationId)).thenReturn(Optional.of(incident));
        // A timeline query can automatically flush the preceding assignment UPDATE.
        when(events.findFirstByOrganisationIdAndIncidentIdOrderByOccurredAtDescIdDesc(organisationId, incidentId))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Fixture membership race",
                        new java.sql.SQLException("Fixture FK conflict", "23503")));
        assertThrows(IncidentConflictException.class, () -> application.changeAssignee("subject", organisationId, incidentId, userId, 0));
    }

    @Test void membershipConflictDuringCommentAppendReturnsAConflict() {
        member(OrganisationRole.ENGINEER);
        when(incidents.findScopeByIdAndOrganisationId(incidentId, organisationId)).thenReturn(Optional.of(mock(IncidentRepository.Scope.class)));
        var incident = Incident.open(organisationId, "Title", "Description", AlertSeverity.HIGH, Instant.parse("2026-10-27T12:00:00Z"), null);
        ReflectionTestUtils.setField(incident, "id", incidentId);
        when(incidents.findByIdAndOrganisationIdForUpdate(incidentId, organisationId)).thenReturn(Optional.of(incident));
        when(events.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("Fixture author race",
                new java.sql.SQLException("Fixture FK conflict", "23503")));
        assertThrows(IncidentConflictException.class, () -> application.addComment("subject", organisationId, incidentId, "Investigation", null));
    }

    @ParameterizedTest @CsvSource({"-1,20", "0,0", "0,-1", "0,101", "2147483647,100"})
    void invalidPaginationNeverBecomesAnUnboundedQuery(int page, int size) {
        assertThrows(InvalidIncidentQueryException.class, () -> new IncidentQuery(null, null, null, page, size, "createdAt,desc"));
    }

    @Test void sortingIsWhitelistedAndHasADeterministicIdTieBreaker() {
        assertThrows(InvalidIncidentQueryException.class, () -> new IncidentQuery(null, null, null, 0, 20, "title,desc"));
        assertThrows(InvalidIncidentQueryException.class, () -> new IncidentQuery(null, null, null, 0, 20, "createdAt,desc;drop table incidents"));
        var query = new IncidentQuery(null, null, null, 1, 5, "createdAt,asc");
        assertEquals(org.springframework.data.domain.Sort.by("createdAt", "id"), query.pageable().getSort());
        assertEquals(5, query.pageable().getOffset());
    }
}
