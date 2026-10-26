package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class AlertEvaluationEngineTest {
    @Mock MonitoredServiceRepository services;
    @Mock HealthCheckResultRepository results;
    @Mock AlertRuleRepository rules;
    @Mock AlertEvaluationStateRepository states;
    @Mock AlertRepository alerts;
    private final UUID organisationId = UUID.randomUUID(), serviceId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-26T12:00:00Z");
    private MonitoredService service;
    private AlertEvaluationEngine engine;

    @BeforeEach void setup() {
        var organisation = Organisation.create("Evaluation unit", "evaluation-unit");
        ReflectionTestUtils.setField(organisation, "id", organisationId);
        service = MonitoredService.create(organisation, "Unit fixture", ServiceType.HTTP,
                Environment.TEST, "https://fixture.example.test", "/health", "Test team");
        ReflectionTestUtils.setField(service, "id", serviceId);
        engine = new AlertEvaluationEngine(services, results, rules, states, alerts, Clock.fixed(now, ZoneOffset.UTC));
    }
    private HealthCheckResult result(HealthStatus status) {
        var result = new HealthCheckResult(organisationId, serviceId, now.plusSeconds(1), now.plusSeconds(1), status, 503, 2, "Fixture failure");
        ReflectionTestUtils.setField(result, "id", UUID.randomUUID());
        when(services.findByIdAndOrganisationIdForUpdate(serviceId, organisationId)).thenReturn(Optional.of(service));
        when(results.findByIdAndOrganisationIdAndMonitoredServiceId(result.getId(), organisationId, serviceId)).thenReturn(Optional.of(result));
        when(states.save(any())).thenAnswer(call -> call.getArgument(0));
        return result;
    }

    @Test void missingServiceAndMissingPersistedResultNeverReachRuleOrStateStorage() {
        engine.evaluate(organisationId, serviceId, UUID.randomUUID());
        verifyNoInteractions(results, rules, states, alerts);
        when(services.findByIdAndOrganisationIdForUpdate(serviceId, organisationId)).thenReturn(Optional.of(service));
        engine.evaluate(organisationId, serviceId, UUID.randomUUID());
        verifyNoInteractions(rules, states, alerts);
        verify(results, never()).findById(any());
    }

    @Test void defaultThresholdCountsOneFailureWithoutCreatingRuleOrAlert() {
        var result = result(HealthStatus.DOWN);
        engine.evaluate(organisationId, serviceId, result.getId());
        ArgumentCaptor<AlertEvaluationState> capture = ArgumentCaptor.forClass(AlertEvaluationState.class);
        verify(states, times(2)).save(capture.capture());
        assertEquals(1, capture.getValue().getConsecutiveFailures());
        assertEquals(result.getId(), capture.getValue().getLastProcessedHealthCheckResultId());
        verify(rules, never()).save(any()); verify(alerts, never()).save(any());
    }

    @Test void configuredThresholdOpensAlertUsingPersistedResultEvidence() {
        var result = result(HealthStatus.DOWN);
        var rule = AlertRule.createDefault(organisationId, serviceId, now);
        rule.changeConfiguration(true, 1, 2, AlertSeverity.CRITICAL, now);
        when(rules.findByOrganisationIdAndMonitoredServiceId(organisationId, serviceId)).thenReturn(Optional.of(rule));
        engine.evaluate(organisationId, serviceId, result.getId());
        ArgumentCaptor<Alert> capture = ArgumentCaptor.forClass(Alert.class); verify(alerts).save(capture.capture());
        assertEquals(AlertStatus.ACTIVE, capture.getValue().getStatus());
        assertEquals(AlertSeverity.CRITICAL, capture.getValue().getSeverity());
        assertEquals(result.getId(), capture.getValue().getTriggeringHealthCheckResultId());
    }

    @Test void disabledServiceConsumesNoFailureAndDoesNotReadOrWriteAlerts() {
        service.disable(); var result = result(HealthStatus.DOWN);
        engine.evaluate(organisationId, serviceId, result.getId());
        ArgumentCaptor<AlertEvaluationState> capture = ArgumentCaptor.forClass(AlertEvaluationState.class);
        verify(states, times(2)).save(capture.capture());
        assertEquals(0, capture.getValue().getConsecutiveFailures()); verifyNoInteractions(alerts);
    }
}
