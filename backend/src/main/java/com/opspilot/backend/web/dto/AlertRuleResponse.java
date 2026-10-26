package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.AlertRule;
import com.opspilot.backend.domain.AlertSeverity;

import java.time.Instant;
import java.util.UUID;

public record AlertRuleResponse(UUID id, UUID organisationId, UUID monitoredServiceId, boolean enabled,
                                int consecutiveFailures, int consecutiveRecoveries, AlertSeverity severity,
                                Instant createdAt, Instant updatedAt) {
    public static AlertRuleResponse from(AlertRule rule) {
        return new AlertRuleResponse(rule.getId(), rule.getOrganisationId(), rule.getMonitoredServiceId(),
                rule.isEnabled(), rule.getConsecutiveFailures(), rule.getConsecutiveRecoveries(), rule.getSeverity(),
                rule.getCreatedAt(), rule.getUpdatedAt());
    }

    // No row exists: identity/timestamps are null, and reading defaults has no persistence side effect.
    public static AlertRuleResponse defaults(UUID organisationId, UUID monitoredServiceId) {
        return new AlertRuleResponse(null, organisationId, monitoredServiceId, AlertRule.DEFAULT_ENABLED,
                AlertRule.DEFAULT_CONSECUTIVE_FAILURES, AlertRule.DEFAULT_CONSECUTIVE_RECOVERIES,
                AlertRule.DEFAULT_SEVERITY, null, null);
    }
}
