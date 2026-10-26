package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.MonitoredServiceNotFoundException;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.domain.exception.InvalidAlertRuleConfigurationException;
import com.opspilot.backend.web.dto.AlertRuleResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
@Transactional
public class AlertRuleService {
    private final AlertRuleRepository rules;
    private final MonitoredServiceRepository services;
    private final AuthenticatedUserService authenticatedUsers;
    private final OrganisationMembershipService memberships;
    private final Clock clock;

    public AlertRuleService(AlertRuleRepository rules, MonitoredServiceRepository services,
                            AuthenticatedUserService authenticatedUsers, OrganisationMembershipService memberships,
                            Clock clock) {
        this.rules = rules;
        this.services = services;
        this.authenticatedUsers = authenticatedUsers;
        this.memberships = memberships;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AlertRuleResponse getRule(String subject, UUID organisationId, UUID monitoredServiceId) {
        User user = authenticatedUsers.findByIdentityProviderSubject(subject);
        memberships.requireMembership(organisationId, user.getId());
        requireHttp(services.findByIdAndOrganisationId(monitoredServiceId, organisationId)
                .orElseThrow(() -> new MonitoredServiceNotFoundException(monitoredServiceId)));
        return rules.findByOrganisationIdAndMonitoredServiceId(organisationId, monitoredServiceId)
                .map(AlertRuleResponse::from).orElseGet(() -> AlertRuleResponse.defaults(organisationId, monitoredServiceId));
    }

    public AlertRuleResponse configureRule(String subject, UUID organisationId, UUID monitoredServiceId,
                                           boolean enabled, int consecutiveFailures, int consecutiveRecoveries,
                                           AlertSeverity severity) {
        User user = authenticatedUsers.findByIdentityProviderSubject(subject);
        memberships.requireMonitoredServiceWritePermission(organisationId, user.getId());
        // Database row lock serializes first-time creates and updates across application instances.
        // READ COMMITTED then observes the previous writer's rule after the parent lock is acquired.
        requireHttp(services.findByIdAndOrganisationIdForUpdate(monitoredServiceId, organisationId)
                .orElseThrow(() -> new MonitoredServiceNotFoundException(monitoredServiceId)));
        var now = clock.instant();
        AlertRule rule = rules.findByOrganisationIdAndMonitoredServiceId(organisationId, monitoredServiceId)
                .orElseGet(() -> AlertRule.createDefault(organisationId, monitoredServiceId, now));
        rule.changeConfiguration(enabled, consecutiveFailures, consecutiveRecoveries, severity, now);
        return AlertRuleResponse.from(rules.save(rule));
    }

    private static void requireHttp(MonitoredService service) {
        if (service.getServiceType() != ServiceType.HTTP) {
            throw new InvalidAlertRuleConfigurationException("Alert rules are supported only for HTTP services.");
        }
    }
}
