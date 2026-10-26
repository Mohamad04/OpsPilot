package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.*;
import com.opspilot.backend.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertQueryServiceTest {
    @Mock AlertRepository alerts;
    @Mock MonitoredServiceRepository services;
    @Mock AuthenticatedUserService users;
    @Mock OrganisationMembershipService memberships;
    @Mock User user;
    private final UUID organisation = UUID.randomUUID(), userId = UUID.randomUUID();
    private AlertQueryService application;
    private AlertQuery query() { return new AlertQuery(null, null, null, null, null, 0, 20); }

    @BeforeEach void setup() {
        application = new AlertQueryService(alerts, services, users, memberships);
    }
    private void authenticated() {
        when(users.findByIdentityProviderSubject("subject")).thenReturn(user);
        when(user.getId()).thenReturn(userId);
    }

    @Test void membershipIsRequiredBeforeAnyAlertOrServiceQuery() {
        authenticated();
        doThrow(new OrganisationMembershipNotFoundException("Organisation membership not found"))
                .when(memberships).requireMembership(organisation, userId);
        assertThrows(OrganisationMembershipNotFoundException.class, () -> application.list("subject", organisation, query()));
        assertThrows(OrganisationMembershipNotFoundException.class, () -> application.details("subject", organisation, UUID.randomUUID()));
        assertThrows(OrganisationMembershipNotFoundException.class, () -> application.summary("subject", organisation));
        verifyNoInteractions(alerts, services);
    }

    @Test void missingOrInactiveLocalUserCannotReachMembershipOrData() {
        when(users.findByIdentityProviderSubject("subject")).thenThrow(new ForbiddenException("Inactive account"));
        assertThrows(ForbiddenException.class, () -> application.list("subject", organisation, query()));
        verifyNoInteractions(memberships, alerts, services);
    }

    @Test void listUsesDatabasePaginationAndFixedStableSortAfterMembershipCheck() {
        authenticated();
        when(alerts.findAll(org.mockito.ArgumentMatchers.<Specification<Alert>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        var response = application.list("subject", organisation, query());
        var page = ArgumentCaptor.forClass(Pageable.class);
        var order = inOrder(memberships, alerts);
        order.verify(memberships).requireMembership(organisation, userId);
        order.verify(alerts).findAll(org.mockito.ArgumentMatchers.<Specification<Alert>>any(), page.capture());
        assertEquals(20, page.getValue().getPageSize()); assertEquals(0, page.getValue().getPageNumber());
        assertEquals(Sort.by(Sort.Order.desc("firstSeenAt"), Sort.Order.desc("id")), page.getValue().getSort());
        assertTrue(response.content().isEmpty());
        verify(alerts, never()).findAll();
    }

    @Test void missingOrForeignServiceFailsBeforeAlertQuery() {
        authenticated(); UUID service = UUID.randomUUID();
        when(services.findByIdAndOrganisationId(service, organisation)).thenReturn(Optional.empty());
        assertThrows(MonitoredServiceNotFoundException.class, () -> application.list("subject", organisation,
                new AlertQuery(null, null, service, null, null, 0, 20)));
        verifyNoInteractions(alerts); verify(services, never()).findById(any());
    }

    @Test void detailsOnlyUsesOrganisationScopedLookupAndUniformNotFound() {
        authenticated(); UUID alert = UUID.randomUUID();
        when(alerts.findByIdAndOrganisationId(alert, organisation)).thenReturn(Optional.empty());
        assertEquals("Alert not found", assertThrows(AlertNotFoundException.class,
                () -> application.details("subject", organisation, alert)).getMessage());
        verify(alerts, never()).findById(any());
    }

    @Test void summaryUsesOnlyBoundedDatabaseGroupsAndIncludesEverySeverity() {
        authenticated();
        var active = mock(AlertRepository.StatusSeverityCount.class);
        when(active.getStatus()).thenReturn(AlertStatus.ACTIVE); when(active.getSeverity()).thenReturn(AlertSeverity.HIGH);
        when(active.getCount()).thenReturn(4L);
        var resolved = mock(AlertRepository.StatusSeverityCount.class);
        when(resolved.getStatus()).thenReturn(AlertStatus.RESOLVED); when(resolved.getCount()).thenReturn(6L);
        when(alerts.countByStatusAndSeverity(organisation)).thenReturn(List.of(active, resolved));
        var response = application.summary("subject", organisation);
        assertEquals(10, response.totalAlerts()); assertEquals(4, response.activeAlerts()); assertEquals(6, response.resolvedAlerts());
        assertEquals(4, response.activeBySeverity().size()); assertEquals(4L, response.activeBySeverity().get(AlertSeverity.HIGH));
        assertEquals(0L, response.activeBySeverity().get(AlertSeverity.CRITICAL));
        verify(alerts, never()).findAll();
    }

    @ParameterizedTest @CsvSource({"-1,20", "0,0", "0,-1", "0,101", "2147483647,100"})
    void invalidPaginationIsRejectedAtApplicationBoundary(int page, int size) {
        assertThrows(InvalidAlertQueryException.class, () -> new AlertQuery(null, null, null, null, null, page, size));
    }

    @Test void invalidRangesAndDatabaseUnsafeDatesAreRejected() {
        Instant at = Instant.parse("2026-10-27T12:00:00Z");
        assertThrows(InvalidAlertQueryException.class, () -> new AlertQuery(null, null, null, at, at, 0, 20));
        assertThrows(InvalidAlertQueryException.class, () -> new AlertQuery(null, null, null, at, at.minusSeconds(1), 0, 20));
        assertThrows(InvalidAlertQueryException.class, () -> new AlertQuery(null, null, null, Instant.MAX, null, 0, 20));
        assertThrows(InvalidAlertQueryException.class, () -> new AlertQuery(null, null, null, null, Instant.MIN, 0, 20));
    }

    @Test void subMicrosecondBoundsAreRoundedUpWithoutChangingExactBounds() {
        Instant at = Instant.parse("2026-10-27T12:00:00.123456Z");
        assertEquals(at, AlertQuery.databaseBound(at));
        assertEquals(at.plusNanos(1000), AlertQuery.databaseBound(at.plusNanos(1)));
        assertNull(AlertQuery.databaseBound(null));
    }
}
