package com.opspilot.backend.application;

import com.opspilot.backend.domain.HealthStatus;
import java.time.Instant;

public record HealthObservation(Instant startedAt, Instant completedAt, HealthStatus status,
                                Integer httpStatusCode, long responseTimeMs, String failureDetail) { }
