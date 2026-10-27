import { ApiError } from "./client";
import { alertOrganisationPath, readAlertResponse } from "./alertApi";
import { alertRuleInputSchema, alertRuleSchema } from "../types/Alert";
import type { AlertRuleInput } from "../types/Alert";

const path = (organisationId: string, serviceId: string) =>
    `${alertOrganisationPath(organisationId)}/monitored-services/${encodeURIComponent(serviceId)}/alert-rule`;
export async function getAlertRule(organisationId: string, serviceId: string, signal?: AbortSignal) {
    const rule = await readAlertResponse(path(organisationId, serviceId), alertRuleSchema, { signal });
    if (rule.organisationId !== organisationId || rule.monitoredServiceId !== serviceId) {
        throw new ApiError(502, "Alert configuration does not match the selected organisation and service.");
    }
    return rule;
}
export async function putAlertRule(organisationId: string, serviceId: string, request: AlertRuleInput) {
    const input = alertRuleInputSchema.parse(request);
    const rule = await readAlertResponse(path(organisationId, serviceId), alertRuleSchema, {
        method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(input),
    });
    if (!rule.id || rule.organisationId !== organisationId || rule.monitoredServiceId !== serviceId
        || (Object.keys(input) as Array<keyof AlertRuleInput>).some((key) => rule[key] !== input[key])) {
        throw new ApiError(502, "The saved alert configuration could not be verified. Reload before retrying.");
    }
    return rule;
}
