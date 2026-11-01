import { z } from "zod";
import { apiFetch, ApiError } from "./client";
import { API_BASE_URL } from "./config";
import { assignmentInputSchema, commentInputSchema, incidentEventPageSchema, incidentEventSchema,
    incidentFiltersSchema, incidentPageSchema, incidentSchema, incidentStatuses } from "../types/Incident";
import type { IncidentFilters, IncidentStatus } from "../types/Incident";

function uuid(value: string) {
    const parsed = z.uuid().safeParse(value);
    if (!parsed.success) throw new ApiError(400, "This incident link contains an invalid identifier.");
    return parsed.data;
}
const path = (org: string) => `${API_BASE_URL}/api/organisations/${encodeURIComponent(uuid(org))}/incidents`;
const resource = (org: string, id: string) => `${path(org)}/${encodeURIComponent(uuid(id))}`;
const scopeError = () => new ApiError(502, "Incident data does not match this organisation or incident. No data is displayed.");
const conflictMessages: Record<string, string> = {
    "Cannot resolve or close an incident while linked alerts are ACTIVE": "Active linked alerts prevent resolution or closure. Allow the alerts to recover before resolving this incident.",
    "Another ongoing incident already exists for this monitored service": "Another ongoing incident already exists for this service. Review it before reopening this incident.",
    "Idempotency key was already used for a different comment": "This comment retry key was used for different content. Start a new comment submission.",
    "Only ongoing incidents can be assigned": "Only ongoing incidents can be assigned. Review the current incident status.",
};
async function read<T>(url: string, schema: z.ZodType<T>, options: RequestInit = {}): Promise<T> {
    const response = await apiFetch(url, options);
    if (!response.ok) {
        let message = response.status === 404 ? "This incident or eligible assignee is unavailable in this organisation."
            : response.status === 400 ? "Check your input and try again." : response.status === 409
                ? "The incident or membership changed. Details are being refreshed; review them before trying again."
                : "Unable to load or save incident data. Please try again.";
        if (response.status === 409) {
            try { const body: unknown = await response.json();
                const error = z.object({ message: z.string().max(500) }).safeParse(body);
                if (error.success) message = conflictMessages[error.data.message] ?? message;
            } catch { /* Use a safe fallback; never show internal diagnostics. */ }
        }
        throw new ApiError(response.status, message);
    }
    let body: unknown;
    try { body = await response.json(); } catch { throw new ApiError(502, "The incident response could not be verified."); }
    const parsed = schema.safeParse(body);
    if (!parsed.success) throw new ApiError(502, "The incident response could not be verified.");
    return parsed.data;
}
const json = (body: unknown, method: string): RequestInit => ({ method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

export async function getIncidents(org: string, filters: IncidentFilters = {}, signal?: AbortSignal) {
    const parsed = incidentFiltersSchema.safeParse(filters);
    if (!parsed.success) throw new ApiError(400, "Choose valid incident filters and pagination values.");
    const selected = parsed.data;
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(selected)) if (value !== undefined) params.set(key, String(value));
    const page = await read(`${path(org)}?${params}`, incidentPageSchema, { signal });
    if (page.page !== selected.page || page.size !== selected.size || new Set(page.content.map(value => value.id)).size !== page.content.length
        || page.content.some(value => value.organisationId !== org || (selected.serviceId && value.monitoredServiceId !== selected.serviceId)
            || (selected.status && value.status !== selected.status) || (selected.severity && value.severity !== selected.severity))) throw scopeError();
    return page;
}
export async function getIncident(org: string, id: string, signal?: AbortSignal) {
    const value = await read(resource(org, id), incidentSchema, { signal });
    if (value.organisationId !== org || value.id !== id) throw scopeError();
    return value;
}
export async function getIncidentEvents(org: string, id: string, page = 0, size = 20, signal?: AbortSignal) {
    incidentFiltersSchema.parse({ page, size });
    const result = await read(`${resource(org, id)}/events?${new URLSearchParams({ page: String(page), size: String(size) })}`, incidentEventPageSchema, { signal });
    if (result.page !== page || result.size !== size || new Set(result.content.map(value => value.id)).size !== result.content.length
        || result.content.some(value => value.organisationId !== org || value.incidentId !== id)) throw scopeError();
    return result;
}
export async function changeIncidentStatus(org: string, id: string, status: IncidentStatus, expectedVersion: number) {
    z.enum(incidentStatuses).parse(status); z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).parse(expectedVersion);
    const value = await read(`${resource(org, id)}/status`, incidentSchema, json({ status, expectedVersion }, "PATCH"));
    if (value.organisationId !== org || value.id !== id || value.status !== status || value.version !== expectedVersion + 1) throw scopeError();
    return value;
}
export async function changeIncidentAssignee(org: string, id: string, assigneeUserId: string | null, expectedVersion: number) {
    const input = assignmentInputSchema.parse({ assigneeUserId: assigneeUserId?.toLowerCase() ?? null, expectedVersion });
    const value = await read(`${resource(org, id)}/assignee`, incidentSchema, json(input, "PATCH"));
    if (value.organisationId !== org || value.id !== id || value.assignedUserId !== input.assigneeUserId
        || ![expectedVersion, expectedVersion + 1].includes(value.version)) throw scopeError();
    return value;
}
export async function addIncidentComment(org: string, id: string, content: string, key: string, userId: string) {
    commentInputSchema.parse({ content }); const input = { content: content.trim() }; z.uuid().parse(key); z.uuid().parse(userId);
    const value = await read(`${resource(org, id)}/comments`, incidentEventSchema,
        { ...json(input, "POST"), headers: { "Content-Type": "application/json", "Idempotency-Key": key } });
    if (value.organisationId !== org || value.incidentId !== id || value.eventType !== "COMMENT_ADDED"
        || value.actorUserId !== userId || value.sourceAlertId !== null || value.details !== content.trim()) throw scopeError();
    return value;
}
