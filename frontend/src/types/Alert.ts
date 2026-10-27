import { z } from "zod";

export const alertStatuses = ["ACTIVE", "RESOLVED"] as const;
export const alertSeverities = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
const id = z.string().min(1);
const instant = z.iso.datetime({ offset: true });
const count = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const threshold = z.number().int().min(1).max(20);
export const alertRuleInputSchema = z.object({
    enabled: z.boolean(), consecutiveFailures: threshold, consecutiveRecoveries: threshold,
    severity: z.enum(alertSeverities),
});
export const alertSchema = z.object({
    id, organisationId: id, monitoredServiceId: id, category: z.literal("HTTP_AVAILABILITY"),
    severity: z.enum(alertSeverities), status: z.enum(alertStatuses),
    firstSeenAt: instant, lastSeenAt: instant, resolvedAt: instant.nullable(), occurrenceCount: count.min(1),
    failureThreshold: threshold, recoveryThreshold: threshold, resolvedAfterRecoveries: threshold.nullable(),
    firstFailureHealthCheckResultId: id, triggeringHealthCheckResultId: id, lastFailureHealthCheckResultId: id,
    resolutionHealthCheckResultId: id.nullable(), triggeringHttpStatusCode: z.number().int().min(100).max(599).nullable(),
    triggeringResponseTimeMs: count,
}).refine((value) => Date.parse(value.lastSeenAt) >= Date.parse(value.firstSeenAt)
    && value.occurrenceCount >= value.failureThreshold
    && (value.status === "ACTIVE"
        ? value.resolvedAt === null && value.resolutionHealthCheckResultId === null && value.resolvedAfterRecoveries === null
        : value.resolvedAt !== null && Date.parse(value.resolvedAt) >= Date.parse(value.lastSeenAt)
            && value.resolutionHealthCheckResultId !== null && value.resolvedAfterRecoveries !== null));
export const alertPageSchema = z.object({
    content: z.array(alertSchema).max(100), page: count, size: z.number().int().min(1).max(100),
    totalElements: count, totalPages: count, first: z.boolean(), last: z.boolean(),
}).refine((value) => value.content.length <= value.size && value.content.length <= value.totalElements
    && value.totalPages === Math.ceil(value.totalElements / value.size) && value.first === (value.page === 0)
    && value.last === (value.page + 1 >= value.totalPages));
export const alertSummarySchema = z.object({
    totalAlerts: count, activeAlerts: count, resolvedAlerts: count,
    activeBySeverity: z.object({ LOW: count, MEDIUM: count, HIGH: count, CRITICAL: count }),
}).refine((value) => value.totalAlerts === value.activeAlerts + value.resolvedAlerts
    && Object.values(value.activeBySeverity).reduce((sum, next) => sum + next, 0) === value.activeAlerts);
export const alertRuleSchema = alertRuleInputSchema.extend({
    id: id.nullable(), organisationId: id, monitoredServiceId: id,
    createdAt: instant.nullable(), updatedAt: instant.nullable(),
}).refine((value) => value.id === null
    ? value.createdAt === null && value.updatedAt === null && value.enabled
        && value.consecutiveFailures === 3 && value.consecutiveRecoveries === 2 && value.severity === "HIGH"
    : value.createdAt !== null && value.updatedAt !== null && Date.parse(value.updatedAt) >= Date.parse(value.createdAt));
export const alertFiltersSchema = z.object({
    status: z.enum(alertStatuses).optional(), severity: z.enum(alertSeverities).optional(), serviceId: id.optional(),
    from: instant.optional(), to: instant.optional(), page: z.number().int().nonnegative().default(0),
    size: z.number().int().min(1).max(100).default(20),
}).refine((value) => (!value.from || !value.to || Date.parse(value.from) < Date.parse(value.to))
    && value.page * value.size <= 2147483647, { message: "Choose a valid date range and page." });
export type Alert = z.infer<typeof alertSchema>;
export type AlertPage = z.infer<typeof alertPageSchema>;
export type AlertSummary = z.infer<typeof alertSummarySchema>;
export type AlertRule = z.infer<typeof alertRuleSchema>;
export type AlertRuleInput = z.infer<typeof alertRuleInputSchema>;
export type AlertFilters = z.input<typeof alertFiltersSchema>;
