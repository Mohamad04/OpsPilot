export interface MonitoredService {
    id: string;
    name: string;
    serviceType: "HTTP" | "TCP" | "ICMP" | "SNMP";
    environment: "DEVELOPMENT"| "TEST"| "STAGING"| "PRODUCTION";
    owner: string;
    enabled: boolean;
    organisationId: string;
    baseUrl: string;
    healthEndpoint: string;
}