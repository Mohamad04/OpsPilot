import type { Alert, AlertPage, AlertRule, AlertSummary } from "../types/Alert";

export const emptyAlertSummary: AlertSummary = { totalAlerts: 0, activeAlerts: 0, resolvedAlerts: 0,
    activeBySeverity: { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 } };
export const defaultAlertRule = (organisationId: string, monitoredServiceId: string): AlertRule => ({
    id: null, organisationId, monitoredServiceId, enabled: true, consecutiveFailures: 3,
    consecutiveRecoveries: 2, severity: "HIGH", createdAt: null, updatedAt: null,
});
export const alertFixture = (organisationId = "alpha", monitoredServiceId = "service", status: Alert["status"] = "ACTIVE", id = "alert-1"): Alert => ({
    id, organisationId, monitoredServiceId, category: "HTTP_AVAILABILITY", severity: "HIGH", status,
    firstSeenAt: "2026-10-27T10:00:00Z", lastSeenAt: "2026-10-27T10:02:00Z", occurrenceCount: 3,
    failureThreshold: 3, recoveryThreshold: 2, resolvedAfterRecoveries: status === "RESOLVED" ? 2 : null,
    resolvedAt: status === "RESOLVED" ? "2026-10-27T10:04:00Z" : null,
    firstFailureHealthCheckResultId: "health-1", triggeringHealthCheckResultId: "health-3", lastFailureHealthCheckResultId: "health-3",
    resolutionHealthCheckResultId: status === "RESOLVED" ? "health-5" : null,
    triggeringHttpStatusCode: 503, triggeringResponseTimeMs: 25,
});
export const alertPage = (content: Alert[] = [], page = 0, size = 20, totalElements = content.length): AlertPage => ({
    content, page, size, totalElements, totalPages: Math.ceil(totalElements / size), first: page === 0,
    last: page + 1 >= Math.ceil(totalElements / size),
});
// New endpoints return empty real-contract fixtures in pre-alerting regression tests.
export function emptyAlertResponse(url: string): unknown | undefined {
    const parsed = new URL(url);
    if (parsed.pathname.endsWith("/alerts/summary")) return emptyAlertSummary;
    const rule = parsed.pathname.match(/\/api\/organisations\/([^/]+)\/monitored-services\/([^/]+)\/alert-rule$/);
    if (rule) return defaultAlertRule(rule[1], rule[2]);
    if (parsed.pathname.endsWith("/alerts")) return alertPage([], Number(parsed.searchParams.get("page") ?? 0), Number(parsed.searchParams.get("size") ?? 20));
    return undefined;
}
