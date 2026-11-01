import type { Incident, IncidentEvent, IncidentStatus } from "../types/Incident";

export const ORG = "11111111-1111-4111-8111-111111111111";
export const OTHER_ORG = "22222222-2222-4222-8222-222222222222";
export const USER = "33333333-3333-4333-8333-333333333333";
export const OTHER_USER = "44444444-4444-4444-8444-444444444444";
export const SERVICE = "55555555-5555-4555-8555-555555555555";
export const INCIDENT = "66666666-6666-4666-8666-666666666666";
export const SECOND_INCIDENT = "77777777-7777-4777-8777-777777777777";
export const EVENT = "88888888-8888-4888-8888-888888888888";
export const ALERT = "99999999-9999-4999-8999-999999999999";
export const KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
export const createdAt = "2026-10-28T12:00:00Z";
export const incidentFixture = (change: Partial<Incident> = {}): Incident => ({
    id: INCIDENT, organisationId: ORG, monitoredServiceId: SERVICE, title: "Payments availability incident",
    description: "Availability checks failed repeatedly.", severity: "HIGH", status: "OPEN", assignedUserId: null,
    createdAt, acknowledgedAt: null, resolvedAt: null, version: 0, ...change,
});
export function inStatus(status: IncidentStatus) {
    return incidentFixture({ status, acknowledgedAt: status === "OPEN" ? null : "2026-10-28T12:01:00Z",
        resolvedAt: status === "RESOLVED" || status === "CLOSED" ? "2026-10-28T12:02:00Z" : null });
}
export const eventFixture = (change: Partial<IncidentEvent> = {}): IncidentEvent => ({
    id: EVENT, organisationId: ORG, incidentId: INCIDENT, eventType: "CREATED", actorUserId: null,
    sourceAlertId: ALERT, occurredAt: createdAt, details: "Incident opened for an ACTIVE alert.",
    previousStatus: null, newStatus: null, previousAssigneeUserId: null, newAssigneeUserId: null, ...change,
});
export function incidentPage<T>(content: T[], page = 0, size = 20, totalElements = content.length) {
    const totalPages = Math.ceil(totalElements / size);
    return { content, page, size, totalElements, totalPages, first: page === 0, last: page + 1 >= totalPages };
}
