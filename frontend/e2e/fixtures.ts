import { test as base, expect } from "@playwright/test";
import type { Page, TestInfo } from "@playwright/test";
import { z } from "zod";
import { incidentSchema, incidentEventPageSchema, incidentPageSchema, membershipContextSchema } from "../src/types/Incident";

export { expect };
export const apiOrigin = new URL(process.env.E2E_API_URL ?? "http://localhost:8080").origin;
export const keycloakOrigin = new URL(process.env.E2E_KEYCLOAK_URL ?? "http://localhost:8082").origin;
const organisationSchema = z.object({ id: z.uuid(), name: z.string(), slug: z.string() }).extend(membershipContextSchema.shape);
export type Organisation = z.infer<typeof organisationSchema>;
export type ApiResult = { status: number; body: unknown };
type Session = { organisations: Organisation[]; api: (method: string, path: string, body?: unknown, idempotencyKey?: string) => Promise<ApiResult> };
export type Workspace = Session & { organisation: Organisation; goto: (path: string) => Promise<void>; incident: () => Promise<z.infer<typeof incidentSchema>> };
export function blocked(info: TestInfo, reason: string): never {
    info.annotations.push({ type: "blocked", description: reason });
    base.skip(true, reason);
    throw new Error("Blocked E2E prerequisite");
}
export async function noOverflow(page: Page) {
    const bounds = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }));
    expect(bounds.scroll).toBeLessThanOrEqual(bounds.width + 1);
}
export const test = base.extend<{ identity: "operator" | "engineer" | "viewer"; session: Session; workspace: Workspace }>({
    identity: ["operator", { option: true }],
    session: async ({ page, identity }, run, info) => {
        const prefix = identity === "operator" ? "E2E" : `E2E_${identity.toUpperCase()}`;
        const username = process.env[`${prefix}_USERNAME`], password = process.env[`${prefix}_PASSWORD`];
        if (!username || !password) blocked(info, `Missing ${prefix}_USERNAME / ${prefix}_PASSWORD for a dedicated identity`);
        let bearer = "";
        page.on("request", request => {
            const url = new URL(request.url());
            if (url.origin === apiOrigin && url.pathname.startsWith("/api/")) {
                const value = request.headers()["authorization"];
                if (value?.startsWith("Bearer ")) bearer = value;
            }
        });
        let organisations: Organisation[];
        try {
            await page.goto("/incidents");
            await page.waitForURL(url => url.origin === keycloakOrigin);
            if (!new URL(page.url()).pathname.includes("/protocol/openid-connect/auth")) throw new Error("Unexpected authentication page");
            await page.locator("#username").fill(username);
            await page.locator("#password").fill(password);
            const responsePromise = page.waitForResponse(response => new URL(response.url()).origin === apiOrigin
                && new URL(response.url()).pathname === "/api/organisations" && response.request().method() === "GET");
            await page.locator("#kc-login").click();
            const response = await responsePromise;
            if (response.status() !== 200 || !bearer) throw new Error("Authenticated organisation request failed");
            const parsed = z.array(organisationSchema).safeParse(await response.json());
            if (!parsed.success) throw new Error("The running backend is missing the Phase 4E membership contract");
            organisations = parsed.data;
        } catch {
            // No raw error/cause, screenshots or traces from authentication (including required actions/MFA).
            await page.close().catch(() => {});
            throw new Error("Dedicated Keycloak login or Phase 4E membership contract verification failed. Verify credentials, required actions and current backend code locally.");
        }
        const api: Session["api"] = async (method, path, body, idempotencyKey) => {
            if (!/^\/api\/organisations(?:\/|\?|$)/.test(path) || path.includes("..")) throw new Error("Unsupported test API path");
            if (method !== "GET") {
                const org = organisations.find(item => item.id === process.env.E2E_ORGANISATION_ID);
                const prefix = `/api/organisations/${org?.id}/incidents/${process.env.E2E_INCIDENT_ID}/`;
                if (process.env.E2E_ALLOW_WRITES !== "1" || !org?.slug.startsWith("opspilot-e2e-") || !path.startsWith(prefix)
                    || !["PATCH", "POST"].includes(method) || !/\/(status|assignee|comments)$/.test(path)) throw new Error("Write request is outside the explicitly designated E2E fixture");
            }
            const response = await fetch(`${apiOrigin}${path}`, { method, redirect: "error", signal: AbortSignal.timeout(10_000),
                headers: { Authorization: bearer, ...(body === undefined ? {} : { "Content-Type": "application/json" }),
                    ...(idempotencyKey ? { "Idempotency-Key": z.uuid().parse(idempotencyKey) } : {}) },
                ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
            return { status: response.status, body: await response.json() };
        };
        await run({ organisations, api });
        bearer = "";
        // Context/page close automatically; no storageState, cookies or access tokens are saved to disk.
    },
    workspace: async ({ page, session }, run, info) => {
        const id = process.env.E2E_ORGANISATION_ID;
        if (!id) blocked(info, "Missing E2E_ORGANISATION_ID for an explicitly designated demo organisation");
        const organisation = session.organisations.find(item => item.id === id);
        if (!organisation) throw new Error("Dedicated identity is not a member of the designated E2E organisation");
        const goto = async (path: string) => {
            await page.goto(path);
            const selector = page.getByRole("combobox", { name: "Organisation", exact: true });
            await expect(selector.locator(`option[value="${organisation.id}"]`)).toHaveCount(1);
            await selector.selectOption(organisation.id);
            await expect(selector).toHaveValue(organisation.id);
        };
        const incident = async () => {
            const id = process.env.E2E_INCIDENT_ID;
            if (!id) blocked(info, "Missing E2E_INCIDENT_ID for a disposable incident created by real alert correlation");
            const response = await session.api("GET", `/api/organisations/${organisation.id}/incidents/${z.uuid().parse(id)}`);
            expect(response.status).toBe(200);
            const result = incidentSchema.parse(response.body);
            expect(result.organisationId).toBe(organisation.id);
            return result;
        };
        await goto("/incidents");
        await run({ ...session, organisation, goto, incident });
        if (info.status !== info.expectedStatus && process.env.E2E_DATA_IS_DEMO === "1" && organisation.slug.startsWith("opspilot-e2e-")) {
            // Deliberately capture only app content, after authentication, with form contents masked.
            const main = page.getByRole("main");
            if (await main.isVisible() && new URL(page.url()).origin !== keycloakOrigin) {
                const path = info.outputPath("application-failure.png");
                await main.screenshot({ path, animations: "disabled", mask: [main.locator("input, textarea")] }).catch(() => {});
                await info.attach("application-content", { path, contentType: "image/png" }).catch(() => {});
            }
        }
    },
});
export async function requireWrites(workspace: Workspace, info: TestInfo) {
    if (process.env.E2E_ALLOW_WRITES !== "1") blocked(info, "Writes require E2E_ALLOW_WRITES=1 and designated disposable fixtures");
    if (!workspace.organisation.slug.startsWith("opspilot-e2e-")) throw new Error("Write tests require an opspilot-e2e- organisation slug");
    const incident = await workspace.incident();
    if (!incident.monitoredServiceId) throw new Error("Write fixture has no monitored service");
    const service = await workspace.api("GET", `/api/organisations/${workspace.organisation.id}/monitored-services/${incident.monitoredServiceId}`);
    const parsed = z.object({ organisationId: z.uuid(), environment: z.literal("TEST"), serviceType: z.literal("HTTP"),
        enabled: z.boolean(), name: z.string().startsWith("opspilot-e2e-") }).parse(service.body);
    expect(service.status).toBe(200); expect(parsed.organisationId).toBe(workspace.organisation.id);
    if (parsed.enabled) blocked(info, "Pause monitoring on the dedicated TEST fixture before writes to avoid background version changes");
    return incident;
}
export async function timeline(workspace: Workspace, incidentId: string, latest = false) {
    const response = await workspace.api("GET", `/api/organisations/${workspace.organisation.id}/incidents/${incidentId}/events?page=0&size=100`);
    expect(response.status).toBe(200); const result = incidentEventPageSchema.parse(response.body);
    if (latest && result.totalPages > 1) {
        const last = await workspace.api("GET", `/api/organisations/${workspace.organisation.id}/incidents/${incidentId}/events?page=${result.totalPages - 1}&size=100`);
        expect(last.status).toBe(200); return incidentEventPageSchema.parse(last.body);
    }
    return result;
}
export async function list(workspace: Workspace, query = "page=0&size=20") {
    const response = await workspace.api("GET", `/api/organisations/${workspace.organisation.id}/incidents?${query}`);
    expect(response.status).toBe(200); return incidentPageSchema.parse(response.body);
}
