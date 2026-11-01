import { z } from "zod";
import { test, expect, apiOrigin, blocked, list, timeline } from "./fixtures";

test("dedicated Keycloak login returns trusted local membership context", async ({ session }, info) => {
    if (!session.organisations.length) blocked(info, "Dedicated identity has no organisation membership; authenticated role contract cannot be verified");
    for (const organisation of session.organisations) {
        expect(z.uuid().safeParse(organisation.currentUserId).success).toBe(true);
        expect(["OWNER", "ADMIN", "ENGINEER", "VIEWER"]).toContain(organisation.currentUserRole);
    }
});
test("organisation selection and incident navigation use real scoped records", async ({ page, workspace }) => {
    await expect(page.getByRole("heading", { name: "Incidents", exact: true })).toBeVisible();
    const data = await list(workspace);
    const region = page.getByRole("region", { name: "Incident registry" });
    await expect(region).toHaveAttribute("aria-busy", "false");
    if (data.content.length) await expect(region.getByRole("link", { name: data.content[0].title, exact: true })).toBeVisible();
    else await expect(region.getByRole("heading", { name: "No incidents recorded" })).toBeVisible();
    await expect(page.getByRole("combobox", { name: "Organisation", exact: true })).toHaveValue(workspace.organisation.id);
});
test("status and severity filters are sent to the backend and reset pagination", async ({ page, workspace }) => {
    await expect(page.getByRole("region", { name: "Incident registry" })).toHaveAttribute("aria-busy", "false");
    await page.getByLabel("Status", { exact: true }).selectOption("RESOLVED");
    await page.getByLabel("Severity", { exact: true }).selectOption("HIGH");
    const responsePromise = page.waitForResponse(response => {
        const url = new URL(response.url()); return url.origin === apiOrigin && url.pathname === `/api/organisations/${workspace.organisation.id}/incidents`
            && url.searchParams.get("status") === "RESOLVED" && url.searchParams.get("severity") === "HIGH";
    });
    await page.getByRole("button", { name: "Apply filters" }).click(); const response = await responsePromise;
    expect(response.status()).toBe(200); const data = await response.json(); expect(data.page).toBe(0);
    for (const item of data.content) { expect(item.organisationId).toBe(workspace.organisation.id); expect(item.status).toBe("RESOLVED"); expect(item.severity).toBe("HIGH"); }
    await expect(page.getByRole("region", { name: "Incident registry" })).toHaveAttribute("aria-busy", "false");
    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(page.getByLabel("Status", { exact: true })).toHaveValue(""); await expect(page.getByLabel("Severity", { exact: true })).toHaveValue("");
});
test("creation sorting is deterministic on real incident data", async ({ page, workspace }, info) => {
    const data = await list(workspace); if (data.totalElements < 2) blocked(info, "Sorting needs at least two designated demo incidents");
    await expect(page.getByRole("region", { name: "Incident registry" })).toHaveAttribute("aria-busy", "false");
    await page.getByLabel("Created", { exact: true }).selectOption("createdAt,asc");
    const pending = page.waitForResponse(response => new URL(response.url()).pathname === `/api/organisations/${workspace.organisation.id}/incidents`
        && new URL(response.url()).searchParams.get("sort") === "createdAt,asc");
    await page.getByRole("button", { name: "Apply filters" }).click(); const response = await pending;
    expect(response.status()).toBe(200); const sorted = await response.json();
    for (let i = 1; i < sorted.content.length; i++) {
        const previous = sorted.content[i - 1], current = sorted.content[i];
        expect(Date.parse(current.createdAt)).toBeGreaterThanOrEqual(Date.parse(previous.createdAt));
        if (previous.createdAt === current.createdAt) expect(current.id.localeCompare(previous.id)).toBeGreaterThan(0);
    }
});
test("pagination fetches a real second page rather than slicing the first page", async ({ page, workspace }, info) => {
    const initial = await list(workspace); if (initial.totalPages < 2) blocked(info, "Pagination needs more than 20 designated demo incidents");
    const pages = page.getByRole("navigation", { name: "Incident pages" });
    const responsePromise = page.waitForResponse(response => new URL(response.url()).pathname === `/api/organisations/${workspace.organisation.id}/incidents`
        && new URL(response.url()).searchParams.get("page") === "1");
    await pages.getByRole("button", { name: "Next", exact: true }).click(); const response = await responsePromise;
    const second = await response.json(); expect(response.status()).toBe(200); expect(second.page).toBe(1);
    expect(second.content.every((item: { id: string }) => !initial.content.some(old => old.id === item.id))).toBe(true);
    await expect(pages).toContainText("Page 2");
});
test("details and chronological timeline display actual alert evidence", async ({ page, workspace }, info) => {
    const incident = await workspace.incident(); await workspace.goto(`/incidents/${incident.id}`);
    await expect(page.getByRole("heading", { name: incident.title, exact: true })).toBeVisible();
    await expect(page.getByRole("complementary", { name: "Incident context and actions" })).toContainText(incident.id);
    const events = await timeline(workspace, incident.id);
    const linked = events.content.slice(0, 20).find(event => event.sourceAlertId);
    if (!linked) blocked(info, "Designated incident has no alert evidence in its first 20 displayed timeline events");
    const link = page.locator(`main a[href="/alerts/${linked.sourceAlertId}"]`).first(); await expect(link).toBeVisible();
    for (let i = 1; i < events.content.length; i++) expect(Date.parse(events.content[i].occurredAt)).toBeGreaterThanOrEqual(Date.parse(events.content[i - 1].occurredAt));
    await link.click(); await expect(page.getByRole("heading", { name: "HTTP availability alert", exact: true })).toBeVisible();
});
test("organisation switching cannot display cached foreign incidents, including delayed responses", async ({ page, workspace }, info) => {
    const secondId = process.env.E2E_SECOND_ORGANISATION_ID;
    if (!secondId) blocked(info, "Missing E2E_SECOND_ORGANISATION_ID for an authorised second demo tenant");
    const second = workspace.organisations.find(item => item.id === secondId);
    if (!second) throw new Error("Dedicated identity lacks membership in the designated second tenant");
    const old = await list(workspace);
    if (!old.content.length) blocked(info, "Cache-isolation verification needs a recorded incident in the first demo tenant");
    // Delay the genuine backend response, never fulfill or replace it with mock data.
    const cdp = await page.context().newCDPSession(page);
    await cdp.send("Network.enable"); await cdp.send("Network.emulateNetworkConditions", { offline: false, latency: 800, downloadThroughput: -1, uploadThroughput: -1 });
    try {
        await expect(page.getByRole("button", { name: "Refresh incidents" })).toBeEnabled();
        const request = page.waitForRequest(request => new URL(request.url()).pathname === `/api/organisations/${workspace.organisation.id}/incidents`);
        await page.getByRole("button", { name: "Refresh incidents" }).click(); await request;
        const response = page.waitForResponse(response => new URL(response.url()).pathname === `/api/organisations/${second.id}/incidents`);
        await page.getByRole("combobox", { name: "Organisation", exact: true }).selectOption(second.id);
        for (const incident of old.content) await expect(page.locator(`main a[href="/incidents/${incident.id}"]`)).toHaveCount(0);
        const result = await response; expect(result.status()).toBe(200);
        const data = await result.json(); expect(data.content.every((item: { organisationId: string }) => item.organisationId === second.id)).toBe(true);
        await expect(page.getByRole("region", { name: "Incident registry" })).toHaveAttribute("aria-busy", "false");
        for (const incident of old.content) await expect(page.locator(`main a[href="/incidents/${incident.id}"]`)).toHaveCount(0);
        if (old.content.length) {
            const foreign = await workspace.api("GET", `/api/organisations/${second.id}/incidents/${old.content[0].id}`); expect(foreign.status).toBe(404);
        }
    } finally { await cdp.send("Network.emulateNetworkConditions", { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 }); await cdp.detach(); }
});
test("monitoring, service registry, alerts and incidents remain navigable", async ({ page, workspace }) => {
    await workspace.goto("/"); await expect(page.getByRole("heading", { name: /Dashboard/, exact: false })).toBeVisible();
    for (const [name, path] of [["Services", "/services"], ["Alerts", "/alerts"], ["Incidents", "/incidents"]]) {
        await page.getByRole("navigation", { name: "Main navigation" }).getByRole("link", { name, exact: true }).click();
        await expect(page).toHaveURL(new RegExp(`${path}$`)); await expect(page.getByRole("heading", { name, exact: true }).first()).toBeVisible();
    }
    // A fresh read proves the final selected tenant still has the same identity/scope.
    const response = await workspace.api("GET", `/api/organisations/${workspace.organisation.id}`); expect(response.status).toBe(200);
    expect(z.object({ id: z.uuid() }).parse(response.body).id).toBe(workspace.organisation.id);
});
