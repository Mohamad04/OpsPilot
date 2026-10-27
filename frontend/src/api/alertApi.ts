import { z } from "zod";
import { apiFetch, ApiError } from "./client";
import { API_BASE_URL } from "./config";
import { alertFiltersSchema, alertPageSchema, alertSchema, alertSummarySchema } from "../types/Alert";
import type { AlertFilters } from "../types/Alert";

export const alertOrganisationPath = (organisationId: string) =>
    `${API_BASE_URL}/api/organisations/${encodeURIComponent(organisationId)}`;
export async function readAlertResponse<T>(url: string, schema: z.ZodType<T>, options: RequestInit = {}): Promise<T> {
    const response = await apiFetch(url, options);
    if (!response.ok) throw new ApiError(response.status, response.status === 404
        ? "The requested alert or service is unavailable in this organisation." : "Unable to load or save alert data. Please try again.");
    let body: unknown;
    try { body = await response.json(); } catch { throw new ApiError(502, "The alert response could not be verified."); }
    const parsed = schema.safeParse(body);
    if (!parsed.success) throw new ApiError(502, "The alert response could not be verified.");
    return parsed.data;
}
const invalidScope = () => new ApiError(502, "Alert data does not match the selected organisation or service.");

export async function getAlerts(organisationId: string, filters: AlertFilters = {}, signal?: AbortSignal, serviceId?: string) {
    const selected = alertFiltersSchema.parse(filters);
    if (serviceId && selected.serviceId && serviceId !== selected.serviceId) throw invalidScope();
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(selected)) {
        if (value !== undefined && !(serviceId && key === "serviceId")) params.set(key, String(value));
    }
    const path = serviceId ? `/monitored-services/${encodeURIComponent(serviceId)}/alerts` : "/alerts";
    const page = await readAlertResponse(`${alertOrganisationPath(organisationId)}${path}?${params}`, alertPageSchema, { signal });
    const expectedService = serviceId ?? selected.serviceId;
    if (page.page !== selected.page || page.size !== selected.size
        || new Set(page.content.map((alert) => alert.id)).size !== page.content.length
        || page.content.some((alert) => alert.organisationId !== organisationId
            || (expectedService && alert.monitoredServiceId !== expectedService)
            || (selected.status && alert.status !== selected.status) || (selected.severity && alert.severity !== selected.severity)
            || (selected.from && Date.parse(alert.firstSeenAt) < Date.parse(selected.from))
            || (selected.to && Date.parse(alert.firstSeenAt) >= Date.parse(selected.to)))) throw invalidScope();
    return page;
}
export async function getAlert(organisationId: string, alertId: string, signal?: AbortSignal) {
    const alert = await readAlertResponse(`${alertOrganisationPath(organisationId)}/alerts/${encodeURIComponent(alertId)}`, alertSchema, { signal });
    if (alert.organisationId !== organisationId || alert.id !== alertId) throw invalidScope();
    return alert;
}
export function getAlertSummary(organisationId: string, signal?: AbortSignal) {
    // The committed summary DTO has no organisation ID: scope comes from the request URL/key.
    return readAlertResponse(`${alertOrganisationPath(organisationId)}/alerts/summary`, alertSummarySchema, { signal });
}
