package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.*;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.domain.exception.InvalidAlertRuleConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertRuleServiceTest {
    @Mock AlertRuleRepository rules;
    @Mock MonitoredServiceRepository services;
    @Mock AuthenticatedUserService users;
    @Mock OrganisationMembershipService memberships;
    @Mock User user;
    @Mock MonitoredService monitoredService;
    private final UUID organisation = UUID.randomUUID(), serviceId = UUID.randomUUID(), userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-26T12:00:00Z");
    private AlertRuleService application;

    @BeforeEach void setup() {
        application = new AlertRuleService(rules, services, users, memberships, Clock.fixed(now, ZoneOffset.UTC));
        when(users.findByIdentityProviderSubject("subject")).thenReturn(user);
        when(user.getId()).thenReturn(userId);
    }

    @Test void getReturnsEffectiveDefaultsWithoutWritingOrLocking() {
        when(services.findByIdAndOrganisationId(serviceId, organisation)).thenReturn(Optional.of(monitoredService));
        when(monitoredService.getServiceType()).thenReturn(ServiceType.HTTP);
        var response = application.getRule("subject", organisation, serviceId);
        assertTrue(response.enabled());
        assertEquals(3, response.consecutiveFailures()); assertEquals(2, response.consecutiveRecoveries());
        assertEquals(AlertSeverity.HIGH, response.severity());
        assertNull(response.id()); assertNull(response.createdAt()); assertNull(response.updatedAt());
        verify(memberships).requireMembership(organisation, userId);
        verify(rules).findByOrganisationIdAndMonitoredServiceId(organisation, serviceId);
        verify(services, never()).findByIdAndOrganisationIdForUpdate(any(), any());
        verify(rules, never()).save(any());
    }

    @Test void existingRuleIsReadUsingBothTenantAndService() {
        var rule = AlertRule.createDefault(organisation, serviceId, now);
        rule.changeConfiguration(false, 5, 4, AlertSeverity.CRITICAL, now);
        when(services.findByIdAndOrganisationId(serviceId, organisation)).thenReturn(Optional.of(monitoredService));
        when(monitoredService.getServiceType()).thenReturn(ServiceType.HTTP);
        when(rules.findByOrganisationIdAndMonitoredServiceId(organisation, serviceId)).thenReturn(Optional.of(rule));
        var response = application.getRule("subject", organisation, serviceId);
        assertFalse(response.enabled()); assertEquals(5, response.consecutiveFailures());
        assertEquals(AlertSeverity.CRITICAL, response.severity());
        verify(rules, never()).findById(any());
    }

    @Test void firstPutLocksParentBeforeLookingForRule() {
        when(services.findByIdAndOrganisationIdForUpdate(serviceId, organisation)).thenReturn(Optional.of(monitoredService));
        when(monitoredService.getServiceType()).thenReturn(ServiceType.HTTP);
        when(rules.save(any())).thenAnswer(call -> call.getArgument(0));
        var response = application.configureRule("subject", organisation, serviceId, true, 7, 4, AlertSeverity.LOW);
        assertEquals(7, response.consecutiveFailures()); assertEquals(4, response.consecutiveRecoveries());
        assertEquals(organisation, response.organisationId()); assertEquals(serviceId, response.monitoredServiceId());
        var order = inOrder(memberships, services, rules);
        order.verify(memberships).requireMonitoredServiceWritePermission(organisation, userId);
        order.verify(services).findByIdAndOrganisationIdForUpdate(serviceId, organisation);
        order.verify(rules).findByOrganisationIdAndMonitoredServiceId(organisation, serviceId);
        order.verify(rules).save(any());
    }

    @Test void putUpdatesTheExistingEntity() {
        var existing = AlertRule.createDefault(organisation, serviceId, now.minusSeconds(1));
        when(services.findByIdAndOrganisationIdForUpdate(serviceId, organisation)).thenReturn(Optional.of(monitoredService));
        when(monitoredService.getServiceType()).thenReturn(ServiceType.HTTP);
        when(rules.findByOrganisationIdAndMonitoredServiceId(organisation, serviceId)).thenReturn(Optional.of(existing));
        when(rules.save(existing)).thenReturn(existing);
        var response = application.configureRule("subject", organisation, serviceId, false, 20, 1, AlertSeverity.MEDIUM);
        assertEquals(now.minusSeconds(1), response.createdAt()); assertEquals(now, response.updatedAt());
        assertFalse(existing.isEnabled());
        verify(rules).save(existing);
    }

    @Test void nonMemberAndViewerCannotReachConfigurationStorage() {
        doThrow(new OrganisationMembershipNotFoundException("Organisation membership not found"))
                .when(memberships).requireMembership(organisation, userId);
        assertThrows(OrganisationMembershipNotFoundException.class, () -> application.getRule("subject", organisation, serviceId));
        doThrow(new InsufficientOrganisationPermissionException("Denied"))
                .when(memberships).requireMonitoredServiceWritePermission(organisation, userId);
        assertThrows(InsufficientOrganisationPermissionException.class,
                () -> application.configureRule("subject", organisation, serviceId, true, 3, 2, AlertSeverity.HIGH));
        verifyNoInteractions(services, rules);
    }

    @Test void missingOrForeignServiceFailsBeforeRuleAccess() {
        assertThrows(MonitoredServiceNotFoundException.class, () -> application.getRule("subject", organisation, serviceId));
        assertThrows(MonitoredServiceNotFoundException.class,
                () -> application.configureRule("subject", organisation, serviceId, true, 3, 2, AlertSeverity.HIGH));
        verifyNoInteractions(rules);
    }

    @ParameterizedTest @EnumSource(value = ServiceType.class, names = "HTTP", mode = EnumSource.Mode.EXCLUDE)
    void nonHttpServicesAreRejectedOnBothReadsAndWrites(ServiceType type) {
        when(services.findByIdAndOrganisationId(serviceId, organisation)).thenReturn(Optional.of(monitoredService));
        when(services.findByIdAndOrganisationIdForUpdate(serviceId, organisation)).thenReturn(Optional.of(monitoredService));
        when(monitoredService.getServiceType()).thenReturn(type);
        assertThrows(InvalidAlertRuleConfigurationException.class, () -> application.getRule("subject", organisation, serviceId));
        assertThrows(InvalidAlertRuleConfigurationException.class,
                () -> application.configureRule("subject", organisation, serviceId, true, 3, 2, AlertSeverity.HIGH));
        verifyNoInteractions(rules);
    }
}
