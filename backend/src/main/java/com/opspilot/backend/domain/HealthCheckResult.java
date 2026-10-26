package com.opspilot.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "health_check_results")
public class HealthCheckResult {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID organisationId;
    @Column(nullable = false) private UUID monitoredServiceId;
    @Column(nullable = false) private Instant startedAt;
    @Column(nullable = false) private Instant completedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 7)
    private HealthStatus status;
    private Integer httpStatusCode;
    @Column(nullable = false) private long responseTimeMs;
    @Column(length = 255) private String failureDetail;

    protected HealthCheckResult() { }

    public HealthCheckResult(UUID organisationId, UUID monitoredServiceId, Instant startedAt,
                             Instant completedAt, HealthStatus status, Integer httpStatusCode,
                             long responseTimeMs, String failureDetail) {
        this.organisationId = organisationId;
        this.monitoredServiceId = monitoredServiceId;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.status = status;
        this.httpStatusCode = httpStatusCode;
        this.responseTimeMs = responseTimeMs;
        this.failureDetail = failureDetail;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getMonitoredServiceId() { return monitoredServiceId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public HealthStatus getStatus() { return status; }
    public Integer getHttpStatusCode() { return httpStatusCode; }
    public long getResponseTimeMs() { return responseTimeMs; }
    public String getFailureDetail() { return failureDetail; }
}
