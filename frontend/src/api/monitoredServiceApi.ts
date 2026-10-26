import type {MonitoredService} from "../types/MonitoredService";
import { API_BASE_URL } from "./config";
import { ApiError, apiFetch } from "./client";
import type {CreateMonitoredServiceRequest} from "../types/CreateMonitoredServiceRequest";

export async function getMonitoredServices(
    organisationId : string
    ): Promise<MonitoredService[]> {
    const url = `${API_BASE_URL}/api/organisations/${organisationId}/monitored-services`;
    const response= await apiFetch(url);
    if (!response.ok) {
        throw new ApiError(response.status, "Could not get monitored services");
    }
    return response.json();
}

export async function getMonitoredService(
    organisationId : string,
    monitoredServiceId : string
): Promise<MonitoredService>{
    const url = `${API_BASE_URL}/api/organisations/${organisationId}/monitored-services/${monitoredServiceId}`;
    const response= await apiFetch(url);
    if (!response.ok) {
        throw new ApiError(response.status, "Could not get monitored service");
    }
    return response.json();
}

export async function enableMonitoredService(
    organisationId: string,
    monitoredServiceId: string
): Promise<MonitoredService> {
    const url = `${API_BASE_URL}/api/organisations/${organisationId}/monitored-services/${monitoredServiceId}/enable`;

    const response= await apiFetch(url, {method:"PATCH"});

    if (!response.ok) {
        throw new Error("Could not enable monitored service");
    }
    return response.json();
}

export async function disableMonitoredService(
    organisationId: string,
    monitoredServiceId: string
): Promise<MonitoredService> {
    const url = `${API_BASE_URL}/api/organisations/${organisationId}/monitored-services/${monitoredServiceId}/disable`;

    const response= await apiFetch(url, {method:"PATCH"});

    if (!response.ok) {
        throw new Error("Could not disable monitored service");
    }
    return response.json();
}

export async function createMonitoredService(
    organisationId : string,
    request: CreateMonitoredServiceRequest
):Promise<MonitoredService>{
    const url= `${API_BASE_URL}/api/organisations/${organisationId}/monitored-services`;
    const response = await apiFetch(url,
        {method:"POST",
         headers:{
            "Content-Type": "application/json"
         },
         body:JSON.stringify(request)});
    if (!response.ok) {
        throw new Error("Could not create monitored service");
    }
    return response.json();
}
