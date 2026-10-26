import type { HealthStatus, LatestHealth } from "../api/healthApi";

export function unknownHealth(organisationId: string, serviceId: string): LatestHealth {
    return { organisationId, monitoredServiceId: serviceId, enabled: true, status: "UNKNOWN", latestResult: null };
}

export function recordedHealth(organisationId: string, serviceId: string, status: HealthStatus = "UP", enabled = true): LatestHealth {
    return { organisationId, monitoredServiceId: serviceId, enabled, status, latestResult: {
        id: `result-${serviceId}`, organisationId, monitoredServiceId: serviceId, status,
        startedAt: "2026-10-26T12:00:00Z", completedAt: "2026-10-26T12:00:00.025Z",
        httpStatusCode: status === "UP" ? 200 : status === "DOWN" ? 503 : null,
        responseTimeMs: 25, failureDetail: status === "UP" ? null : status === "DOWN" ? "Unexpected HTTP status: 503." : "Target blocked by outbound HTTP policy.",
    } };
}
