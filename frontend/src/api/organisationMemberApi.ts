import { z } from "zod";
import { apiFetch, ApiError } from "./client";
import { API_BASE_URL } from "./config";
import { memberPaginationSchema, organisationMemberPageSchema, organisationMemberSchema, organisationRoleSchema } from "../types/OrganisationMember";
import type { OrganisationRole } from "../types/OrganisationMember";

function uuid(value: string) {
    const parsed = z.uuid().safeParse(value);
    if (!parsed.success) throw new ApiError(400, "Choose a valid local organisation, membership or user identifier.");
    return parsed.data.toLowerCase();
}
const path = (org: string) => `${API_BASE_URL}/api/organisations/${uuid(org)}/members`;
const scopeError = () => new ApiError(502, "Membership data does not match the requested organisation. No data is displayed.");
async function send(url: string, options?: RequestInit) {
    const response = await apiFetch(url, options);
    if (!response.ok) throw new ApiError(response.status,
        response.status === 403 ? "Your organisation role does not permit this membership change."
            : response.status === 404 ? "This organisation membership or eligible local user is unavailable."
            : response.status === 409 ? "The membership already exists or changed concurrently. Refresh before trying again."
            : "Unable to load or change organisation members. Please try again.");
    return response;
}
async function read<T>(response: Response, schema: z.ZodType<T>): Promise<T> {
    try { return schema.parse(await response.json()); }
    catch { throw new ApiError(502, "The membership response could not be verified."); }
}
const json = (method: string, body: unknown): RequestInit => ({ method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

export async function getOrganisationMembers(org: string, page = 0, size = 20, signal?: AbortSignal) {
    const selected = memberPaginationSchema.safeParse({ page, size });
    if (!selected.success) throw new ApiError(400, "Choose valid member pagination values.");
    const result = await read(await send(`${path(org)}?${new URLSearchParams({ page: String(page), size: String(size) })}`, { signal }), organisationMemberPageSchema);
    if (result.page !== page || result.size !== size || result.content.some(member => member.organisationId !== uuid(org))
        || new Set(result.content.map(member => member.id)).size !== result.content.length
        || new Set(result.content.map(member => member.userId)).size !== result.content.length) throw scopeError();
    return result;
}
export async function addOrganisationMember(org: string, userId: string, role: OrganisationRole) {
    const target = uuid(userId); organisationRoleSchema.parse(role);
    const result = await read(await send(path(org), json("POST", { userId: target, role })), organisationMemberSchema);
    if (result.organisationId !== uuid(org) || result.userId !== target || result.role !== role) throw scopeError();
    return result;
}
export async function changeOrganisationMemberRole(org: string, membershipId: string, role: OrganisationRole) {
    const id = uuid(membershipId); organisationRoleSchema.parse(role);
    const result = await read(await send(`${path(org)}/${id}`, json("PATCH", { role })), organisationMemberSchema);
    if (result.organisationId !== uuid(org) || result.id !== id || result.role !== role) throw scopeError();
    return result;
}
export async function removeOrganisationMember(org: string, membershipId: string) {
    await send(`${path(org)}/${uuid(membershipId)}`, { method: "DELETE" });
}
