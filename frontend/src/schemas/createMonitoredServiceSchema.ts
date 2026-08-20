import {z} from 'zod';

export const serviceTypes = [
    "HTTP",
    "TCP",
    "ICMP",
    "SNMP",
] as const;

export const environmentValues = [
    "DEVELOPMENT",
    "TEST",
    "STAGING",
    "PRODUCTION",
] as const;

export const createMonitoredServiceSchema = z.object({
    name: z.string()
        .trim()
        .min(1, "Name must not be blank.")
        .max(120, "Name must not exceed 120 characters."),

    serviceType: z.enum(serviceTypes),

    environment: z.enum(environmentValues),

    baseUrl: z.httpUrl("Base URL must be a valid HTTP/HTTPS URL, e.g. https://example.com.")
        .max(2048, "Base URL must not exceed 2048 characters."),

    healthEndpoint: z.string()
        .trim()
        .min(1, "Health endpoint must not be blank.")
        .max(2048, "Health endpoint must not exceed 2048 characters.")
        .startsWith("/", "Health endpoint must start with '/'."),

    owner: z.string()
        .trim()
        .min(1, "Owner must not be blank.")
        .max(100, "Owner must not exceed 100 characters."),
});

export type CreateMonitoredServiceFormData =
    z.infer<typeof createMonitoredServiceSchema>;