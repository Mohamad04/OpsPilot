import { z } from "zod";

export const organisationRoles = ["OWNER", "ADMIN", "ENGINEER", "VIEWER"] as const;
export const organisationRoleSchema = z.enum(organisationRoles);
export type OrganisationRole = z.infer<typeof organisationRoleSchema>;
export const organisationMemberSchema = z.object({
    id: z.uuid(), organisationId: z.uuid(), userId: z.uuid(), displayName: z.string().min(1).max(100),
    role: organisationRoleSchema,
});
export type OrganisationMember = z.infer<typeof organisationMemberSchema>;
export const memberPaginationSchema = z.object({
    page: z.number().int().min(0).max(2147483647).default(0), size: z.number().int().min(1).max(100).default(20),
});
export const organisationMemberPageSchema = z.object({
    content: z.array(organisationMemberSchema).max(100), page: z.number().int().nonnegative(),
    size: z.number().int().min(1).max(100), totalElements: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
    totalPages: z.number().int().nonnegative(), first: z.boolean(), last: z.boolean(),
}).refine(value => value.content.length <= value.size && value.content.length <= value.totalElements
    && value.totalPages === Math.ceil(value.totalElements / value.size)
    && value.first === (value.page === 0) && value.last === (value.page >= value.totalPages - 1));
