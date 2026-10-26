import { z } from "zod";
import { ApiError, apiFetch } from "./client";
import { API_BASE_URL } from "./config";

const status = z.enum(["UP", "DOWN", "UNKNOWN"]);
const resultSchema = z.object({
    id: z.string().min(1), organisationId: z.string().min(1), monitoredServiceId: z.string().min(1),
    startedAt: z.iso.datetime({ offset: true }), completedAt: z.iso.datetime({ offset: true }),
    status, httpStatusCode: z.number().int().min(100).max(599).nullable(),
    responseTimeMs: z.number().int().nonnegative(), failureDetail: z.string().max(255).nullable(),
}).refine((result) => result.status !== "UP" || (result.httpStatusCode !== null && result.httpStatusCode >= 200 && result.httpStatusCode < 300));
const latestSchema = z.object({
    organisationId: z.string(), monitoredServiceId: z.string(), enabled: z.boolean(), status,
    latestResult: resultSchema.nullable(),
}).refine((latest) => latest.latestResult ? latest.status === latest.latestResult.status : latest.status === "UNKNOWN");

export type HealthStatus = z.infer<typeof status>;
export type HealthResult = z.infer<typeof resultSchema>;
export type LatestHealth = z.infer<typeof latestSchema>;

// The existing backend has per-service endpoints. Bound dashboard fan-out without changing its contracts.
let active = 0;
const waiting: Array<() => void> = [];
async function withSlot<T>(signal: AbortSignal | undefined, request: () => Promise<T>): Promise<T> {
    await new Promise<void>((resolve, reject) => {
        const cancelled = () => {
            const index = waiting.indexOf(start);
            if (index !== -1) waiting.splice(index, 1);
            reject(new DOMException("Monitoring request cancelled", "AbortError"));
        };
        const start = () => {
            signal?.removeEventListener("abort", cancelled);
            if (signal?.aborted) { cancelled(); return; }
            active += 1;
            resolve();
        };
        if (signal?.aborted) { cancelled(); return; }
        if (active < 4) start();
        else { waiting.push(start); signal?.addEventListener("abort", cancelled, { once: true }); }
    });
    try {
        if (signal?.aborted) throw new DOMException("Monitoring request cancelled", "AbortError");
        return await request();
    } finally { active -= 1; waiting.shift()?.(); }
}

const path = (organisationId: string, serviceId: string) =>
    `${API_BASE_URL}/api/organisations/${encodeURIComponent(organisationId)}/monitored-services/${encodeURIComponent(serviceId)}/health`;
const matches = (result: { organisationId: string; monitoredServiceId: string }, organisationId: string, serviceId: string) =>
    result.organisationId === organisationId && result.monitoredServiceId === serviceId;
const invalid = () => new ApiError(502, "Health response could not be verified for the selected organisation and service.");

export async function getLatestHealth(organisationId: string, serviceId: string, signal?: AbortSignal): Promise<LatestHealth> {
    return withSlot(signal, async () => {
        const response = await apiFetch(`${path(organisationId, serviceId)}/latest`, { signal });
        if (!response.ok) throw new ApiError(response.status, "Could not load the latest health check.");
        const parsed = latestSchema.safeParse(await response.json());
        if (!parsed.success || !matches(parsed.data, organisationId, serviceId)
            || (parsed.data.latestResult && !matches(parsed.data.latestResult, organisationId, serviceId))) throw invalid();
        return parsed.data;
    });
}

export async function getHealthHistory(organisationId: string, serviceId: string, limit = 20, signal?: AbortSignal): Promise<HealthResult[]> {
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new RangeError("History limit must be between 1 and 100.");
    return withSlot(signal, async () => {
        const response = await apiFetch(`${path(organisationId, serviceId)}/history?limit=${limit}`, { signal });
        if (!response.ok) throw new ApiError(response.status, "Could not load health-check history.");
        const parsed = z.array(resultSchema).max(limit).safeParse(await response.json());
        if (!parsed.success || parsed.data.some((result) => !matches(result, organisationId, serviceId))) throw invalid();
        return parsed.data;
    });
}
