import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { addIncidentComment, changeIncidentAssignee, changeIncidentStatus, getIncident, getIncidentEvents, getIncidents } from "../api/incidentApi";
import { ApiError } from "../api/client";
import type { IncidentFilters, IncidentStatus } from "../types/Incident";

export const incidentKeys = {
    root: (org: string) => ["incidents", org] as const,
    lists: (org: string) => ["incidents", org, "list"] as const,
    list: (org: string, filters: IncidentFilters) => ["incidents", org, "list", filters] as const,
    detail: (org: string, id: string) => ["incidents", org, "detail", id] as const,
    events: (org: string, id: string) => ["incidents", org, "events", id] as const,
    eventPage: (org: string, id: string, page: number) => ["incidents", org, "events", id, { page, size: 20 }] as const,
};
const options = { retry: false as const, staleTime: 0, placeholderData: undefined,
    refetchOnWindowFocus: false, refetchOnMount: "always" as const, refetchInterval: false as const };
export function useIncidents(org: string, filters: IncidentFilters) {
    const query = useQuery({ ...options, queryKey: incidentKeys.list(org, filters), queryFn: ({ signal }) => getIncidents(org, filters, signal) });
    return { ...query, data: query.isError || !query.isFetchedAfterMount ? undefined : query.data };
}
export function useIncident(org: string, id: string) {
    const query = useQuery({ ...options, queryKey: incidentKeys.detail(org, id), queryFn: ({ signal }) => getIncident(org, id, signal) });
    return { ...query, data: query.isError || !query.isFetchedAfterMount ? undefined : query.data };
}
export function useIncidentEvents(org: string, id: string, page: number) {
    const query = useQuery({ ...options, queryKey: incidentKeys.eventPage(org, id, page), queryFn: ({ signal }) => getIncidentEvents(org, id, page, 20, signal) });
    return { ...query, data: query.isError || !query.isFetchedAfterMount ? undefined : query.data };
}
export function useIncidentWrites(org: string, id: string) {
    const client = useQueryClient();
    const refresh = () => Promise.all([
        client.invalidateQueries({ queryKey: incidentKeys.detail(org, id) }),
        client.invalidateQueries({ queryKey: incidentKeys.events(org, id) }),
        client.invalidateQueries({ queryKey: incidentKeys.lists(org) }),
    ]);
    const onError = async (error: Error) => {
        if (error instanceof ApiError && [403, 404, 409].includes(error.status)) await refresh();
        if (error instanceof ApiError && error.status === 403) await client.invalidateQueries({ queryKey: ["organisations"] });
    };
    const status = useMutation({ retry: false, mutationFn: (input: { status: IncidentStatus; expectedVersion: number }) =>
        changeIncidentStatus(org, id, input.status, input.expectedVersion), onSuccess: refresh, onError });
    const assignment = useMutation({ retry: false, mutationFn: (input: { assigneeUserId: string | null; expectedVersion: number }) =>
        changeIncidentAssignee(org, id, input.assigneeUserId, input.expectedVersion), onSuccess: refresh, onError });
    const comment = useMutation({ retry: false, mutationFn: (input: { content: string; key: string; userId: string }) =>
        addIncidentComment(org, id, input.content, input.key, input.userId),
        onSuccess: () => client.invalidateQueries({ queryKey: incidentKeys.events(org, id) }), onError });
    return { status, assignment, comment };
}
