import { queryOptions, useQuery } from "@tanstack/react-query";
import { getHealthHistory, getLatestHealth } from "../api/healthApi";

export const healthKeys = {
    service: (organisationId: string, serviceId: string) => ["monitored-service-health", organisationId, serviceId] as const,
    latest: (organisationId: string, serviceId: string) => [...healthKeys.service(organisationId, serviceId), "latest"] as const,
    history: (organisationId: string, serviceId: string, limit: number) => [...healthKeys.service(organisationId, serviceId), "history", limit] as const,
};

const refresh = {
    staleTime: 30_000, refetchIntervalInBackground: false, retry: false as const,
    placeholderData: undefined,
};

export function latestHealthOptions(organisationId: string, serviceId: string, enabled: boolean, polling: boolean) {
    return queryOptions({
        ...refresh, queryKey: healthKeys.latest(organisationId, serviceId),
        queryFn: ({ signal }) => getLatestHealth(organisationId, serviceId, signal), enabled,
        refetchInterval: polling ? 60_000 : false,
    });
}

export function useLatestHealth(organisationId: string, serviceId: string, supported: boolean, polling: boolean) {
    return useQuery(latestHealthOptions(organisationId, serviceId, supported, polling));
}

export function useHealthHistory(organisationId: string, serviceId: string, supported: boolean, polling: boolean) {
    return useQuery({
        ...refresh, queryKey: healthKeys.history(organisationId, serviceId, 20),
        queryFn: ({ signal }) => getHealthHistory(organisationId, serviceId, 20, signal), enabled: supported,
        refetchInterval: polling ? 60_000 : false,
    });
}
