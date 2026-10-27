import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getAlert, getAlerts, getAlertSummary } from "../api/alertApi";
import { getAlertRule, putAlertRule } from "../api/alertRuleApi";
import { getMonitoredServices } from "../api/monitoredServiceApi";
import { ApiError } from "../api/client";
import type { AlertFilters, AlertRuleInput } from "../types/Alert";

export const alertKeys = {
    organisation: (org: string) => ["alerts", org] as const,
    list: (org: string, filters: AlertFilters, serviceId?: string) => ["alerts", org, "list", serviceId ?? null, filters] as const,
    detail: (org: string, id: string) => ["alerts", org, "detail", id] as const,
    summary: (org: string) => ["alerts", org, "summary"] as const,
    rule: (org: string, service: string) => ["alert-rule", org, service] as const,
};
const options = { retry: false as const, staleTime: 0, placeholderData: undefined, refetchOnWindowFocus: false,
    refetchOnMount: "always" as const, refetchIntervalInBackground: false, refetchInterval: false as const };

export function useAlerts(org: string, filters: AlertFilters, serviceId?: string) {
    const query = useQuery({ ...options, queryKey: alertKeys.list(org, filters, serviceId),
        queryFn: ({ signal }) => getAlerts(org, filters, signal, serviceId) });
    return { ...query, data: query.isError || !query.isFetchedAfterMount ? undefined : query.data };
}
export function useAlert(org: string, id: string) {
    const query = useQuery({ ...options, queryKey: alertKeys.detail(org, id), queryFn: ({ signal }) => getAlert(org, id, signal) });
    return { ...query, data: query.isError || !query.isFetchedAfterMount ? undefined : query.data };
}
export function useAlertSummary(org: string) {
    const query = useQuery({ ...options, queryKey: alertKeys.summary(org), queryFn: ({ signal }) => getAlertSummary(org, signal) });
    return { ...query, data: query.isError || !query.isFetchedAfterMount ? undefined : query.data };
}
export function useAlertRule(org: string, serviceId: string) {
    const query = useQuery({ ...options, queryKey: alertKeys.rule(org, serviceId), queryFn: ({ signal }) => getAlertRule(org, serviceId, signal) });
    return { ...query, data: query.isError || !query.isFetchedAfterMount ? undefined : query.data };
}
export function useSaveAlertRule(org: string, serviceId: string) {
    const client = useQueryClient();
    return useMutation({
        mutationFn: (input: AlertRuleInput) => putAlertRule(org, serviceId, input), retry: false,
        onSuccess: async (saved) => {
            client.setQueryData(alertKeys.rule(org, serviceId), saved);
            await Promise.all([
                client.invalidateQueries({ queryKey: alertKeys.rule(org, serviceId) }),
                client.invalidateQueries({ queryKey: alertKeys.organisation(org) }),
            ]);
        },
    });
}
export function useAlertServices(org: string) {
    const query = useQuery({ ...options, queryKey: ["monitored-services", org],
        queryFn: async ({ signal }) => {
            const services = await getMonitoredServices(org, signal);
            if (!Array.isArray(services) || services.some((service) => service.organisationId !== org || !service.id || typeof service.name !== "string")) {
                throw new ApiError(502, "Services do not match the selected organisation.");
            }
            return services;
        } });
    return { ...query, data: query.isError || !query.isFetchedAfterMount ? undefined : query.data };
}
