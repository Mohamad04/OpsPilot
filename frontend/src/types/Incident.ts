import { z } from "zod";
import { alertSeverities } from "./Alert";

export const incidentStatuses = ["OPEN", "ACKNOWLEDGED", "INVESTIGATING", "MITIGATED", "RESOLVED", "CLOSED"] as const;
export const organisationRoles = ["OWNER", "ADMIN", "ENGINEER", "VIEWER"] as const;
const id = z.uuid();
const instant = z.iso.datetime({ offset: true });
const count = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
export const incidentSchema = z.object({
    id, organisationId: id, monitoredServiceId: id.nullable(), title: z.string().min(1).max(200),
    description: z.string().min(1).max(4000), severity: z.enum(alertSeverities), status: z.enum(incidentStatuses),
    assignedUserId: id.nullable(), createdAt: instant, acknowledgedAt: instant.nullable(), resolvedAt: instant.nullable(), version: count,
}).refine(value => (value.acknowledgedAt === null || Date.parse(value.acknowledgedAt) >= Date.parse(value.createdAt))
    && (value.status === "OPEN" ? value.acknowledgedAt === null && value.resolvedAt === null
        : value.acknowledgedAt !== null && (value.status === "RESOLVED" || value.status === "CLOSED"
            ? value.resolvedAt !== null && Date.parse(value.resolvedAt) >= Date.parse(value.acknowledgedAt)
            : value.resolvedAt === null)));

export const incidentEventSchema = z.object({
    id, organisationId: id, incidentId: id, eventType: z.string().min(1).max(64),
    actorUserId: id.nullable(), sourceAlertId: id.nullable(), occurredAt: instant, details: z.string().min(1).max(4000),
    previousStatus: z.enum(incidentStatuses).nullable(), newStatus: z.enum(incidentStatuses).nullable(),
    previousAssigneeUserId: id.nullish().transform(value => value ?? null),
    newAssigneeUserId: id.nullish().transform(value => value ?? null),
});
const pagination = { page: count, size: z.number().int().min(1).max(100), totalElements: count,
    totalPages: count, first: z.boolean(), last: z.boolean() };
const pageValid = (value: { content: unknown[]; page: number; size: number; totalElements: number; totalPages: number; first: boolean; last: boolean }) =>
    value.content.length <= value.size && value.content.length <= value.totalElements
    && value.totalPages === Math.ceil(value.totalElements / value.size) && value.first === (value.page === 0)
    && value.last === (value.page + 1 >= value.totalPages);
export const incidentPageSchema = z.object({ ...pagination, content: z.array(incidentSchema).max(100) }).refine(pageValid);
export const incidentEventPageSchema = z.object({ ...pagination, content: z.array(incidentEventSchema).max(100) }).refine(pageValid);
export const incidentFiltersSchema = z.object({
    status: z.enum(incidentStatuses).optional(), severity: z.enum(alertSeverities).optional(), serviceId: id.optional(),
    page: count.default(0), size: z.number().int().min(1).max(100).default(20),
    sort: z.enum(["createdAt,desc", "createdAt,asc"]).default("createdAt,desc"),
}).refine(value => value.page * value.size <= 2147483647);
export const commentInputSchema = z.object({ content: z.string().max(4000, "Use 4000 characters or fewer.")
    .refine(value => value.trim().length > 0, "Write a comment before submitting.")
    .refine(value => !value.includes("\0"), "Remove unsupported NUL characters.") });
export const assignmentInputSchema = z.object({ assigneeUserId: id.nullable(), expectedVersion: count });
export const membershipContextSchema = z.object({ currentUserId: id, currentUserRole: z.enum(organisationRoles) });
export type Incident = z.infer<typeof incidentSchema>;
export type IncidentEvent = z.infer<typeof incidentEventSchema>;
export type IncidentStatus = Incident["status"];
export type IncidentFilters = z.input<typeof incidentFiltersSchema>;
export type IncidentPage = z.infer<typeof incidentPageSchema>;
export type IncidentEventPage = z.infer<typeof incidentEventPageSchema>;
export type IncidentMember = z.infer<typeof membershipContextSchema>;
export const ongoing = (status: IncidentStatus) => !["RESOLVED", "CLOSED"].includes(status);
export const statusLabels: Record<IncidentStatus, string> = {
    OPEN: "Open", ACKNOWLEDGED: "Acknowledged", INVESTIGATING: "Investigating", MITIGATED: "Mitigated", RESOLVED: "Resolved", CLOSED: "Closed",
};
export const incidentTransitions: Record<IncidentStatus, IncidentStatus[]> = {
    OPEN: ["ACKNOWLEDGED"], ACKNOWLEDGED: ["INVESTIGATING", "RESOLVED"], INVESTIGATING: ["MITIGATED", "RESOLVED"],
    MITIGATED: ["INVESTIGATING", "RESOLVED"], RESOLVED: ["CLOSED", "INVESTIGATING"], CLOSED: ["INVESTIGATING"],
};
