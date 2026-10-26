package com.opspilot.backend.application;

import java.util.UUID;

// Detached configuration snapshot: no database transaction is held during network I/O.
public record HealthCheckTarget(UUID serviceId, UUID organisationId, String baseUrl, String healthEndpoint) { }
