package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AlertEvaluationEngine {
    private final MonitoredServiceRepository services;
    private final HealthCheckResultRepository results;
    private final AlertRuleRepository rules;
    private final AlertEvaluationStateRepository states;
    private final AlertRepository alerts;
    private final Clock clock;

    public AlertEvaluationEngine(MonitoredServiceRepository services, HealthCheckResultRepository results,
                                  AlertRuleRepository rules, AlertEvaluationStateRepository states,
                                  AlertRepository alerts, Clock clock) {
        this.services = services; this.results = results; this.rules = rules;
        this.states = states; this.alerts = alerts; this.clock = clock;
    }

    public void evaluate(UUID organisationId, UUID serviceId, UUID resultId) {
        var service = services.findByIdAndOrganisationIdForUpdate(serviceId, organisationId).orElse(null);
        if (service == null) return;
        var result = results.findByIdAndOrganisationIdAndMonitoredServiceId(resultId, organisationId, serviceId).orElse(null);
        if (result == null) return; // Never accept an unpersisted or foreign result supplied by a caller.
        Instant now = clock.instant();
        AlertRule rule = effectiveRule(organisationId, serviceId, now);
        String key = configurationKey(service, rule);
        AlertEvaluationState state = state(organisationId, serviceId, key, now);
        if (!key.equals(state.getConfigurationKey())) state.resetConfiguration(key, boundary(organisationId, serviceId, now), now);
        boolean enabled = service.getServiceType() == ServiceType.HTTP && rule.allowsEvaluation(service.isEnabled());
        if (state.consume(result, enabled, now)) {
            Alert active = alerts.findByOrganisationIdAndMonitoredServiceIdAndCategoryAndStatus(
                    organisationId, serviceId, AlertCategory.HTTP_AVAILABILITY, AlertStatus.ACTIVE).orElse(null);
            switch (result.getStatus()) {
                case DOWN -> {
                    if (active != null) active.recordFailure(result);
                    else if (state.getConsecutiveFailures() >= rule.getConsecutiveFailures()) {
                        active = Alert.open(result, state.getFirstFailureHealthCheckResultId(), state.getFirstFailureAt(),
                                state.getConsecutiveFailures(), rule.getConsecutiveFailures(), rule.getConsecutiveRecoveries(), rule.getSeverity());
                    }
                    if (active != null) alerts.save(active);
                }
                case UP -> {
                    if (active != null && state.getConsecutiveRecoveries() >= rule.getConsecutiveRecoveries()) {
                        active.resolve(result, rule.getConsecutiveRecoveries()); alerts.save(active);
                    }
                }
                case UNKNOWN -> { /* State consumed the interruption; keep all alert history untouched. */ }
            }
        }
        states.save(state);
    }

    // Called inside existing configuration transactions. The same parent lock prevents races with evaluation.
    public void resetForConfigurationChange(UUID organisationId, UUID serviceId) {
        var service = services.findByIdAndOrganisationIdForUpdate(serviceId, organisationId).orElse(null);
        if (service == null) return;
        Instant now = clock.instant();
        String key = configurationKey(service, effectiveRule(organisationId, serviceId, now));
        AlertEvaluationState state = state(organisationId, serviceId, key, now);
        state.resetConfiguration(key, boundary(organisationId, serviceId, now), now);
        states.save(state);
    }

    private AlertRule effectiveRule(UUID organisationId, UUID serviceId, Instant now) {
        return rules.findByOrganisationIdAndMonitoredServiceId(organisationId, serviceId)
                .orElseGet(() -> AlertRule.createDefault(organisationId, serviceId, now));
    }

    private AlertEvaluationState state(UUID organisationId, UUID serviceId, String key, Instant now) {
        return states.findByOrganisationIdAndMonitoredServiceId(organisationId, serviceId)
                .orElseGet(() -> states.save(AlertEvaluationState.create(organisationId, serviceId, key, now)));
    }

    private Instant boundary(UUID organisationId, UUID serviceId, Instant now) {
        return results.findFirstByOrganisationIdAndMonitoredServiceIdOrderByStartedAtDescIdDesc(organisationId, serviceId)
                .map(result -> result.getStartedAt().isAfter(now) ? result.getStartedAt() : now).orElse(now);
    }

    private static String configurationKey(MonitoredService service, AlertRule rule) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(service.getServiceType().name(), Boolean.toString(service.isEnabled()),
                    service.getBaseUrl(), service.getHealthEndpoint(), Boolean.toString(rule.isEnabled()),
                    Integer.toString(rule.getConsecutiveFailures()), Integer.toString(rule.getConsecutiveRecoveries()), rule.getSeverity().name())) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is required", exception); }
    }
}
