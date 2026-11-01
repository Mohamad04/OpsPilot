import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { addOrganisationMember, changeOrganisationMemberRole, getOrganisationMembers, removeOrganisationMember } from "../api/organisationMemberApi";
import type { OrganisationRole } from "../types/OrganisationMember";
import { ApiError } from "../api/client";
import { incidentKeys } from "./useIncidents";

export const organisationMemberKeys = {
    root: (org: string) => ["organisation-members", org] as const,
    list: (org: string, page: number, size: number) => ["organisation-members", org, { page, size }] as const,
};
export function useOrganisationMembers(org: string, page = 0, size = 20) {
    const query = useQuery({ queryKey: organisationMemberKeys.list(org, page, size),
        queryFn: ({ signal }) => getOrganisationMembers(org, page, size, signal), enabled: !!org,
        retry: false, staleTime: 0, placeholderData: undefined, refetchOnMount: "always", refetchOnWindowFocus: false });
    return { ...query, data: query.isError || !query.isFetchedAfterMount ? undefined : query.data };
}
export function useOrganisationMembershipWrites(org: string) {
    const client = useQueryClient();
    const refresh = () => Promise.all([
        client.invalidateQueries({ queryKey: organisationMemberKeys.root(org) }),
        client.invalidateQueries({ queryKey: ["organisations"] }),
        // Removal can unassign incidents and increment their versions in PostgreSQL.
        client.invalidateQueries({ queryKey: incidentKeys.root(org) }),
    ]);
    const onError = async (error: Error) => {
        if (error instanceof ApiError && [403, 404, 409].includes(error.status)) await refresh();
    };
    const add = useMutation({ retry: false, mutationFn: (input: { userId: string; role: OrganisationRole }) =>
        addOrganisationMember(org, input.userId, input.role), onSuccess: refresh, onError });
    const role = useMutation({ retry: false, mutationFn: (input: { membershipId: string; role: OrganisationRole }) =>
        changeOrganisationMemberRole(org, input.membershipId, input.role), onSuccess: refresh, onError });
    const remove = useMutation({ retry: false, mutationFn: (id: string) => removeOrganisationMember(org, id), onSuccess: refresh, onError });
    return { add, role, remove };
}
