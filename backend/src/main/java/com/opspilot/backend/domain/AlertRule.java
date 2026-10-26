package com.opspilot.backend.domain;

import com.opspilot.backend.domain.exception.InvalidAlertRuleConfigurationException;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "alert_rules", uniqueConstraints =
        @UniqueConstraint(name = "alert_rules_monitored_service_key", columnNames = "monitored_service_id"))
public class AlertRule {
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_CONSECUTIVE_FAILURES = 3;
    public static final int DEFAULT_CONSECUTIVE_RECOVERIES = 2;
    public static final AlertSeverity DEFAULT_SEVERITY = AlertSeverity.HIGH;
    public static final int MIN_THRESHOLD = 1;
    public static final int MAX_THRESHOLD = 20;

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @NotNull @Column(name = "organisation_id", nullable = false, updatable = false)
    private UUID organisationId;
    @NotNull @Column(name = "monitored_service_id", nullable = false, updatable = false)
    private UUID monitoredServiceId;
    @Column(nullable = false)
    private boolean enabled;
    @Min(MIN_THRESHOLD) @Max(MAX_THRESHOLD)
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;
    @Min(MIN_THRESHOLD) @Max(MAX_THRESHOLD)
    @Column(name = "consecutive_recoveries", nullable = false)
    private int consecutiveRecoveries;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private AlertSeverity severity;
    @NotNull @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @NotNull @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AlertRule() { }

    private AlertRule(UUID organisationId, UUID monitoredServiceId, Instant now) {
        if (organisationId == null || monitoredServiceId == null) {
            throw new InvalidAlertRuleConfigurationException("Organisation and monitored service are required.");
        }
        this.organisationId = organisationId;
        this.monitoredServiceId = monitoredServiceId;
        this.enabled = DEFAULT_ENABLED;
        this.consecutiveFailures = DEFAULT_CONSECUTIVE_FAILURES;
        this.consecutiveRecoveries = DEFAULT_CONSECUTIVE_RECOVERIES;
        this.severity = DEFAULT_SEVERITY;
        this.createdAt = timestamp(now);
        this.updatedAt = createdAt;
    }

    public static AlertRule createDefault(UUID organisationId, UUID monitoredServiceId, Instant now) {
        return new AlertRule(organisationId, monitoredServiceId, now);
    }

    public void changeConfiguration(boolean enabled, int consecutiveFailures, int consecutiveRecoveries,
                                    AlertSeverity severity, Instant now) {
        validateThreshold(consecutiveFailures, "Consecutive failures");
        validateThreshold(consecutiveRecoveries, "Consecutive recoveries");
        if (severity == null) throw new InvalidAlertRuleConfigurationException("Severity is required.");
        Instant changedAt = timestamp(now);
        if (this.enabled == enabled && this.consecutiveFailures == consecutiveFailures
                && this.consecutiveRecoveries == consecutiveRecoveries && this.severity == severity) return;
        this.enabled = enabled;
        this.consecutiveFailures = consecutiveFailures;
        this.consecutiveRecoveries = consecutiveRecoveries;
        this.severity = severity;
        // Keep timestamps valid even if the wall clock moves backwards.
        this.updatedAt = changedAt.isBefore(updatedAt) ? updatedAt : changedAt;
    }

    // Configuration gate for a future evaluator; this method never schedules or generates alerts.
    public boolean allowsEvaluation(boolean monitoredServiceEnabled) {
        return enabled && monitoredServiceEnabled;
    }

    private static void validateThreshold(int value, String field) {
        if (value < MIN_THRESHOLD || value > MAX_THRESHOLD) {
            throw new InvalidAlertRuleConfigurationException(field + " must be between 1 and 20.");
        }
    }

    private static Instant timestamp(Instant now) {
        return Objects.requireNonNull(now, "Configuration timestamp is required").truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getMonitoredServiceId() { return monitoredServiceId; }
    public boolean isEnabled() { return enabled; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public int getConsecutiveRecoveries() { return consecutiveRecoveries; }
    public AlertSeverity getSeverity() { return severity; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
