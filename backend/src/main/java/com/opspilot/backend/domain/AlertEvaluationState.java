package com.opspilot.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "alert_evaluation_states", uniqueConstraints =
        @UniqueConstraint(name = "alert_evaluation_states_service_key", columnNames = "monitored_service_id"))
public class AlertEvaluationState {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, updatable = false) private UUID organisationId;
    @Column(nullable = false, updatable = false) private UUID monitoredServiceId;
    @Column(nullable = false) private int consecutiveFailures;
    @Column(nullable = false) private int consecutiveRecoveries;
    private UUID firstFailureHealthCheckResultId;
    private Instant firstFailureAt;
    private UUID lastProcessedHealthCheckResultId;
    private Instant lastProcessedStartedAt;
    @Column(nullable = false, length = 64) private String configurationKey;
    private Instant configurationChangedAt;
    @Column(nullable = false) private Instant updatedAt;

    protected AlertEvaluationState() { }

    public static AlertEvaluationState create(UUID organisationId, UUID serviceId, String configurationKey, Instant now) {
        AlertEvaluationState state = new AlertEvaluationState();
        state.organisationId = Objects.requireNonNull(organisationId);
        state.monitoredServiceId = Objects.requireNonNull(serviceId);
        state.configurationKey = Objects.requireNonNull(configurationKey);
        state.updatedAt = time(now);
        return state;
    }

    public void resetConfiguration(String key, Instant boundary, Instant now) {
        configurationKey = Objects.requireNonNull(key);
        Instant cutoff = time(boundary);
        if (configurationChangedAt != null && cutoff.isBefore(configurationChangedAt)) cutoff = configurationChangedAt;
        if (lastProcessedStartedAt != null && cutoff.isBefore(lastProcessedStartedAt)) cutoff = lastProcessedStartedAt;
        configurationChangedAt = cutoff;
        clearStreaks();
        touch(now);
    }

    // Returns false for duplicates, older/equal-start observations, disabled evaluation or a configuration barrier.
    public boolean consume(HealthCheckResult result, boolean enabled, Instant now) {
        if (!organisationId.equals(result.getOrganisationId()) || !monitoredServiceId.equals(result.getMonitoredServiceId())) {
            throw new IllegalArgumentException("Health result scope does not match evaluation state");
        }
        UUID resultId = Objects.requireNonNull(result.getId());
        Instant startedAt = time(result.getStartedAt());
        if (resultId.equals(lastProcessedHealthCheckResultId)
                || (lastProcessedStartedAt != null && !startedAt.isAfter(lastProcessedStartedAt))) return false;
        lastProcessedHealthCheckResultId = resultId;
        lastProcessedStartedAt = startedAt;
        touch(now);
        if (!enabled || (configurationChangedAt != null && !startedAt.isAfter(configurationChangedAt))) {
            clearStreaks();
            return false;
        }
        switch (Objects.requireNonNull(result.getStatus())) {
            case DOWN -> {
                if (consecutiveFailures == 0) { firstFailureAt = startedAt; firstFailureHealthCheckResultId = resultId; }
                if (consecutiveFailures < Integer.MAX_VALUE) consecutiveFailures++;
                consecutiveRecoveries = 0;
            }
            case UP -> {
                clearFailures();
                if (consecutiveRecoveries < Integer.MAX_VALUE) consecutiveRecoveries++;
            }
            case UNKNOWN -> clearStreaks();
        }
        return true;
    }

    private void clearFailures() { consecutiveFailures = 0; firstFailureAt = null; firstFailureHealthCheckResultId = null; }
    private void clearStreaks() { clearFailures(); consecutiveRecoveries = 0; }
    private void touch(Instant now) { Instant value = time(now); if (value.isAfter(updatedAt)) updatedAt = value; }
    private static Instant time(Instant value) { return Objects.requireNonNull(value).truncatedTo(ChronoUnit.MICROS); }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getMonitoredServiceId() { return monitoredServiceId; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public int getConsecutiveRecoveries() { return consecutiveRecoveries; }
    public UUID getFirstFailureHealthCheckResultId() { return firstFailureHealthCheckResultId; }
    public Instant getFirstFailureAt() { return firstFailureAt; }
    public UUID getLastProcessedHealthCheckResultId() { return lastProcessedHealthCheckResultId; }
    public Instant getLastProcessedStartedAt() { return lastProcessedStartedAt; }
    public String getConfigurationKey() { return configurationKey; }
    public Instant getConfigurationChangedAt() { return configurationChangedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
