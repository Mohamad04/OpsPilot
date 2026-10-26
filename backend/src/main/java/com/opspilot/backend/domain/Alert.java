package com.opspilot.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "alerts")
public class Alert {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, updatable = false) private UUID organisationId;
    @Column(nullable = false, updatable = false) private UUID monitoredServiceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32, updatable = false)
    private AlertCategory category;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8, updatable = false)
    private AlertSeverity severity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private AlertStatus status;
    @Column(nullable = false, updatable = false) private Instant firstSeenAt;
    @Column(nullable = false) private Instant lastSeenAt;
    private Instant resolvedAt;
    @Column(nullable = false) private long occurrenceCount;
    @Column(nullable = false, updatable = false) private int failureThreshold;
    @Column(nullable = false, updatable = false) private int recoveryThreshold;
    private Integer resolvedAfterRecoveries;
    @Column(nullable = false, updatable = false) private UUID firstFailureHealthCheckResultId;
    @Column(nullable = false, updatable = false) private UUID triggeringHealthCheckResultId;
    @Column(nullable = false) private UUID lastFailureHealthCheckResultId;
    private UUID resolutionHealthCheckResultId;
    @Column(updatable = false) private Integer triggeringHttpStatusCode;
    @Column(nullable = false, updatable = false) private long triggeringResponseTimeMs;
    @Column(length = 255, updatable = false) private String triggeringFailureDetail;

    protected Alert() { }

    public static Alert open(HealthCheckResult trigger, UUID firstFailureId, Instant firstSeenAt,
                             int failures, int failureThreshold, int recoveryThreshold, AlertSeverity severity) {
        requireStatus(trigger, HealthStatus.DOWN);
        requireThreshold(failureThreshold); requireThreshold(recoveryThreshold);
        if (failures < failureThreshold) throw new IllegalArgumentException("Failure threshold has not been reached");
        Alert alert = new Alert();
        alert.organisationId = Objects.requireNonNull(trigger.getOrganisationId());
        alert.monitoredServiceId = Objects.requireNonNull(trigger.getMonitoredServiceId());
        alert.category = AlertCategory.HTTP_AVAILABILITY;
        alert.severity = Objects.requireNonNull(severity);
        alert.status = AlertStatus.ACTIVE;
        alert.firstSeenAt = Objects.requireNonNull(firstSeenAt).truncatedTo(ChronoUnit.MICROS);
        alert.lastSeenAt = trigger.getStartedAt().truncatedTo(ChronoUnit.MICROS);
        if (alert.firstSeenAt.isAfter(alert.lastSeenAt)) throw new IllegalArgumentException("Failure timestamps are out of order");
        alert.occurrenceCount = failures;
        alert.failureThreshold = failureThreshold;
        alert.recoveryThreshold = recoveryThreshold;
        alert.firstFailureHealthCheckResultId = Objects.requireNonNull(firstFailureId);
        alert.triggeringHealthCheckResultId = trigger.getId();
        alert.lastFailureHealthCheckResultId = trigger.getId();
        alert.triggeringHttpStatusCode = trigger.getHttpStatusCode();
        alert.triggeringResponseTimeMs = trigger.getResponseTimeMs();
        alert.triggeringFailureDetail = trigger.getFailureDetail();
        return alert;
    }

    public void recordFailure(HealthCheckResult result) {
        requireActiveResult(result, HealthStatus.DOWN);
        lastSeenAt = result.getStartedAt().truncatedTo(ChronoUnit.MICROS);
        lastFailureHealthCheckResultId = result.getId();
        if (occurrenceCount < Long.MAX_VALUE) occurrenceCount++;
    }

    public void resolve(HealthCheckResult result, int recoveriesRequired) {
        requireActiveResult(result, HealthStatus.UP);
        requireThreshold(recoveriesRequired);
        status = AlertStatus.RESOLVED;
        resolvedAt = result.getStartedAt().truncatedTo(ChronoUnit.MICROS);
        resolutionHealthCheckResultId = result.getId();
        resolvedAfterRecoveries = recoveriesRequired;
    }

    private void requireActiveResult(HealthCheckResult result, HealthStatus expected) {
        if (status != AlertStatus.ACTIVE) throw new IllegalStateException("A resolved alert cannot be changed or reopened");
        requireStatus(result, expected);
        if (!organisationId.equals(result.getOrganisationId()) || !monitoredServiceId.equals(result.getMonitoredServiceId())) {
            throw new IllegalArgumentException("Health evidence must belong to this alert's organisation and service");
        }
        if (!result.getStartedAt().truncatedTo(ChronoUnit.MICROS).isAfter(lastSeenAt)) {
            throw new IllegalArgumentException("Health evidence must be newer than the last matching failure");
        }
    }

    private static void requireStatus(HealthCheckResult result, HealthStatus expected) {
        Objects.requireNonNull(result);
        Objects.requireNonNull(result.getId(), "Persisted health evidence is required");
        Objects.requireNonNull(result.getStartedAt());
        if (result.getStatus() != expected) throw new IllegalArgumentException("Unexpected health evidence status");
        if (result.getResponseTimeMs() < 0) throw new IllegalArgumentException("Invalid response time");
    }

    private static void requireThreshold(int value) {
        if (value < AlertRule.MIN_THRESHOLD || value > AlertRule.MAX_THRESHOLD) throw new IllegalArgumentException("Invalid alert threshold");
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getMonitoredServiceId() { return monitoredServiceId; }
    public AlertCategory getCategory() { return category; }
    public AlertSeverity getSeverity() { return severity; }
    public AlertStatus getStatus() { return status; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public long getOccurrenceCount() { return occurrenceCount; }
    public int getFailureThreshold() { return failureThreshold; }
    public int getRecoveryThreshold() { return recoveryThreshold; }
    public Integer getResolvedAfterRecoveries() { return resolvedAfterRecoveries; }
    public UUID getFirstFailureHealthCheckResultId() { return firstFailureHealthCheckResultId; }
    public UUID getTriggeringHealthCheckResultId() { return triggeringHealthCheckResultId; }
    public UUID getLastFailureHealthCheckResultId() { return lastFailureHealthCheckResultId; }
    public UUID getResolutionHealthCheckResultId() { return resolutionHealthCheckResultId; }
    public Integer getTriggeringHttpStatusCode() { return triggeringHttpStatusCode; }
    public long getTriggeringResponseTimeMs() { return triggeringResponseTimeMs; }
    public String getTriggeringFailureDetail() { return triggeringFailureDetail; }
}
