import { z } from "zod";

type Environment = Record<string, string | undefined>;
type Status = "missing" | "invalid" | "present" | "default" | "disabled";
export type EnvironmentCheck = { name: string; status: Status; message: string };
export function inspectE2EEnvironment(environment: Environment) {
    const checks: EnvironmentCheck[] = [];
    const add = (name: string, status: Status, message: string) => checks.push({ name, status, message });
    const credentialKeys = ["E2E_USERNAME", "E2E_PASSWORD", "E2E_ENGINEER_USERNAME", "E2E_ENGINEER_PASSWORD", "E2E_VIEWER_USERNAME", "E2E_VIEWER_PASSWORD"];
    for (const name of credentialKeys) add(name, environment[name]?.trim() ? "present" : "missing",
        environment[name]?.trim() ? "Supplied locally; authentication is not verified." : "Supply the dedicated test identity credential locally.");
    const usernames = ["E2E_USERNAME", "E2E_ENGINEER_USERNAME", "E2E_VIEWER_USERNAME"];
    for (const name of usernames) {
        const value = environment[name]?.trim().toLowerCase();
        if (value && usernames.some(other => other !== name && environment[other]?.trim().toLowerCase() === value)) {
            const entry = checks.find(item => item.name === name)!;
            entry.status = "invalid"; entry.message = "Use distinct dedicated identities for operational, ENGINEER and VIEWER access.";
        }
    }
    for (const name of ["E2E_ORGANISATION_ID", "E2E_INCIDENT_ID", "E2E_SECOND_ORGANISATION_ID", "E2E_ASSIGNEE_USER_ID"]) {
        const value = environment[name];
        add(name, !value ? "missing" : z.uuid().safeParse(value).success ? "present" : "invalid",
            !value ? "Supply the real, approved fixture UUID after provisioning." : "Must be a local OpsPilot resource UUID; existence and access are not verified here.");
    }
    if (environment.E2E_ORGANISATION_ID && environment.E2E_SECOND_ORGANISATION_ID
        && environment.E2E_ORGANISATION_ID.toLowerCase() === environment.E2E_SECOND_ORGANISATION_ID.toLowerCase()) {
        const entry = checks.find(item => item.name === "E2E_SECOND_ORGANISATION_ID")!;
        entry.status = "invalid"; entry.message = "Tenant isolation requires a different second organisation.";
    }
    const origins: Record<string, string> = { E2E_BASE_URL: "http://localhost:5173", E2E_API_URL: "http://localhost:8080", E2E_KEYCLOAK_URL: "http://localhost:8082" };
    for (const [name, fallback] of Object.entries(origins)) {
        let valid = false;
        try {
            const url = new URL(environment[name] || fallback);
            valid = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname) && ["http:", "https:"].includes(url.protocol) && !url.username && !url.password;
        } catch { /* No values or parser diagnostics are returned. */ }
        add(name, !valid ? "invalid" : environment[name] ? "present" : "default", "Use a local development origin without embedded credentials.");
    }
    for (const name of ["E2E_ALLOW_WRITES", "E2E_DATA_IS_DEMO", "E2E_SCREENSHOTS", "E2E_REQUIRE_COMPLETE"]) {
        const value = environment[name] ?? "0";
        const valid = value === "0" || value === "1";
        add(name, !valid ? "invalid" : value === "1" ? "present" : name === "E2E_REQUIRE_COMPLETE" ? "default" : "disabled",
            name === "E2E_REQUIRE_COMPLETE" ? "The acceptance command always enforces strict completion."
                : "Enable only after the dedicated fixtures and clean demonstration data have been approved.");
    }
    return { configurationComplete: checks.every(item => ["present", "default"].includes(item.status)),
        liveVerificationPerformed: false, checks };
}
