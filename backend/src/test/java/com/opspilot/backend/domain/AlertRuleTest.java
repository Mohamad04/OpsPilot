package com.opspilot.backend.domain;

import com.opspilot.backend.domain.exception.InvalidAlertRuleConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AlertRuleTest {
    private final UUID organisation = UUID.randomUUID(), service = UUID.randomUUID();
    private final Instant created = Instant.parse("2026-10-26T12:00:00Z");

    @Test void defaultsAreEnabledWithThreeFailuresTwoRecoveriesAndHighSeverity() {
        var rule = AlertRule.createDefault(organisation, service, created);
        assertEquals(organisation, rule.getOrganisationId());
        assertEquals(service, rule.getMonitoredServiceId());
        assertTrue(rule.isEnabled());
        assertEquals(3, rule.getConsecutiveFailures());
        assertEquals(2, rule.getConsecutiveRecoveries());
        assertEquals(AlertSeverity.HIGH, rule.getSeverity());
        assertEquals(created, rule.getCreatedAt());
        assertEquals(created, rule.getUpdatedAt());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 21, Integer.MAX_VALUE})
    void invalidFailuresCannotPartiallyChangeConfiguration(int failures) {
        var rule = AlertRule.createDefault(organisation, service, created);
        assertThrows(InvalidAlertRuleConfigurationException.class,
                () -> rule.changeConfiguration(false, failures, 1, AlertSeverity.LOW, created.plusSeconds(1)));
        assertTrue(rule.isEnabled());
        assertEquals(3, rule.getConsecutiveFailures());
        assertEquals(created, rule.getUpdatedAt());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 21, Integer.MAX_VALUE})
    void invalidRecoveriesAreRejected(int recoveries) {
        var rule = AlertRule.createDefault(organisation, service, created);
        assertThrows(InvalidAlertRuleConfigurationException.class,
                () -> rule.changeConfiguration(true, 3, recoveries, AlertSeverity.HIGH, created));
        assertEquals(2, rule.getConsecutiveRecoveries());
    }

    @ParameterizedTest @EnumSource(AlertSeverity.class)
    void validSeveritiesAndThresholdBoundariesAreAccepted(AlertSeverity severity) {
        var rule = AlertRule.createDefault(organisation, service, created);
        rule.changeConfiguration(false, 1, 20, severity, created.plusSeconds(1));
        assertFalse(rule.isEnabled());
        assertEquals(1, rule.getConsecutiveFailures());
        assertEquals(20, rule.getConsecutiveRecoveries());
        assertEquals(severity, rule.getSeverity());
        assertEquals(created, rule.getCreatedAt());
        assertEquals(created.plusSeconds(1), rule.getUpdatedAt());
        rule.changeConfiguration(true, 20, 1, severity, created.plusSeconds(2));
        assertEquals(20, rule.getConsecutiveFailures());
        assertEquals(1, rule.getConsecutiveRecoveries());
    }

    @Test void missingSeverityAndIdentityAreRejected() {
        var rule = AlertRule.createDefault(organisation, service, created);
        assertThrows(InvalidAlertRuleConfigurationException.class,
                () -> rule.changeConfiguration(true, 3, 2, null, created));
        assertThrows(InvalidAlertRuleConfigurationException.class, () -> AlertRule.createDefault(null, service, created));
        assertThrows(InvalidAlertRuleConfigurationException.class, () -> AlertRule.createDefault(organisation, null, created));
    }

    @Test void identicalConfigurationDoesNotChangeTimestamps() {
        var rule = AlertRule.createDefault(organisation, service, created);
        rule.changeConfiguration(true, 3, 2, AlertSeverity.HIGH, created.plusSeconds(5));
        assertEquals(created, rule.getUpdatedAt());
    }

    @Test void timestampsUseDatabasePrecisionAndDoNotMoveBackwards() {
        var rule = AlertRule.createDefault(organisation, service, created.plusNanos(123456789));
        assertEquals(created.plusNanos(123456000), rule.getCreatedAt());
        rule.changeConfiguration(false, 3, 2, AlertSeverity.HIGH, created.minusSeconds(1));
        assertEquals(rule.getCreatedAt(), rule.getUpdatedAt());
    }

    @Test void serviceDisableAlwaysBlocksTheConfigurationGateIndependentlyOfRuleFlag() {
        var rule = AlertRule.createDefault(organisation, service, created);
        assertTrue(rule.allowsEvaluation(true));
        assertFalse(rule.allowsEvaluation(false));
        rule.changeConfiguration(false, 3, 2, AlertSeverity.HIGH, created);
        assertFalse(rule.allowsEvaluation(true));
        assertFalse(rule.allowsEvaluation(false));
    }
}
