import type { MonitoredService } from "./MonitoredService";

export interface CreateMonitoredServiceRequest {
    name: string;
    serviceType: MonitoredService["serviceType"];
    environment: MonitoredService["environment"];
    baseUrl: string;
    healthEndpoint: string;
    owner: string;
}