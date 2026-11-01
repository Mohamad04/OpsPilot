import type { Page } from "@playwright/test";
import { z } from "zod";
import { test, expect, apiOrigin, blocked, requireWrites, timeline } from "./fixtures";
import { incidentSchema, incidentEventSchema, statusLabels } from "../src/types/Incident";
import type { IncidentStatus } from "../src/types/Incident";

const nextOperationalState: Partial<Record<IncidentStatus, IncidentStatus>> = {
    OPEN: "ACKNOWLEDGED", ACKNOWLEDGED: "INVESTIGATING", INVESTIGATING: "MITIGATED", MITIGATED: "INVESTIGATING",
};
async function change(page: Page, org: string, id: string, label: string) {
    await page.getByRole("region", { name: "Lifecycle", exact: true }).getByRole("button", { name: label, exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Change incident status?" });
    await expect(dialog.getByRole("button", { name: "Cancel", exact: true })).toBeFocused();
    const response = page.waitForResponse(response => new URL(response.url()).origin === apiOrigin
        && new URL(response.url()).pathname === `/api/organisations/${org}/incidents/${id}/status` && response.request().method() === "PATCH");
    await dialog.getByRole("button", { name: "Confirm change" }).click(); return response;
}
test("a valid confirmed lifecycle transition persists its human timeline event", async ({ page, workspace }, info) => {
    const before = await requireWrites(workspace, info); const next = nextOperationalState[before.status];
    if (!next) blocked(info, "Lifecycle write fixture must be an ongoing incident");
    expect(["OWNER", "ADMIN", "ENGINEER"]).toContain(workspace.organisation.currentUserRole);
    await workspace.goto(`/incidents/${before.id}`);
    const response = await change(page, workspace.organisation.id, before.id, statusLabels[next]); expect(response.status()).toBe(200);
    const after = incidentSchema.parse(await response.json()); expect(after.version).toBe(before.version + 1); expect(after.status).toBe(next);
    await expect(page.getByRole("status").filter({ hasText: "Incident status updated." })).toBeVisible();
    const events = await timeline(workspace, before.id, true);
    expect(events.content.some(event => event.eventType === "STATUS_CHANGED" && event.actorUserId === workspace.organisation.currentUserId
        && event.previousStatus === before.status && event.newStatus === next)).toBe(true);
});
test("two real tabs reject a stale lifecycle version with HTTP 409 and refresh without replay", async ({ page, workspace, context }, info) => {
    const before = await requireWrites(workspace, info); const next = nextOperationalState[before.status];
    if (!next) blocked(info, "Version-conflict write fixture must be ongoing");
    await workspace.goto(`/incidents/${before.id}`);
    const other = await context.newPage();
    try {
        await other.goto(`/incidents/${before.id}`);
        await other.getByRole("combobox", { name: "Organisation", exact: true }).selectOption(workspace.organisation.id);
        await expect(other.getByRole("region", { name: "Lifecycle", exact: true }).getByRole("button", { name: statusLabels[next], exact: true })).toBeEnabled();
        expect((await change(page, workspace.organisation.id, before.id, statusLabels[next])).status()).toBe(200);
        let writes = 0; other.on("request", request => { if (new URL(request.url()).pathname.endsWith(`/${before.id}/status`) && request.method() === "PATCH") writes++; });
        const stale = await change(other, workspace.organisation.id, before.id, statusLabels[next]); expect(stale.status()).toBe(409);
        await expect(other.getByRole("alert").filter({ hasText: /changed|refresh/i })).toBeVisible();
        const state = await workspace.incident(); expect(state.version).toBe(before.version + 1);
        const metadata = other.getByRole("complementary", { name: "Incident context and actions" });
        await expect(metadata.locator("dl > div").filter({ has: other.locator("dt", { hasText: /^Version$/ }) }).locator("dd")).toHaveText(String(state.version));
        expect(writes).toBe(1);
    } finally { await other.close(); }
});
test("OWNER or ADMIN can assign, reassign and unassign an eligible demo member", async ({ page, workspace }, info) => {
    const before = await requireWrites(workspace, info);
    if (!nextOperationalState[before.status]) blocked(info, "Assignment write fixture must be an ongoing incident");
    expect(["OWNER", "ADMIN"]).toContain(workspace.organisation.currentUserRole);
    const target = process.env.E2E_ASSIGNEE_USER_ID;
    if (!target) blocked(info, "Missing E2E_ASSIGNEE_USER_ID for a known eligible local demo member");
    z.uuid().parse(target);
    if (target.toLowerCase() === workspace.organisation.currentUserId) throw new Error("Reassignment fixture needs a different eligible member");
    if (before.assignedUserId !== null) blocked(info, "Assignment write fixture must initially be unassigned");
    await workspace.goto(`/incidents/${before.id}`);
    for (const assignee of [workspace.organisation.currentUserId, target]) {
        await page.getByLabel("Local user ID", { exact: true }).fill(assignee);
        await page.getByRole("button", { name: "Assign member", exact: true }).click();
        const pending = page.waitForResponse(response => new URL(response.url()).pathname.endsWith(`/${before.id}/assignee`) && response.request().method() === "PATCH");
        await page.getByRole("dialog").getByRole("button", { name: "Confirm change" }).click(); expect((await pending).status()).toBe(200);
        await expect(page.getByRole("button", { name: "Unassign", exact: true })).toBeEnabled();
        expect((await workspace.incident()).assignedUserId).toBe(assignee.toLowerCase());
    }
    await page.getByRole("button", { name: "Unassign", exact: true }).click();
    const pending = page.waitForResponse(response => new URL(response.url()).pathname.endsWith(`/${before.id}/assignee`) && response.request().method() === "PATCH");
    await page.getByRole("dialog").getByRole("button", { name: "Confirm change" }).click(); expect((await pending).status()).toBe(200);
    expect((await workspace.incident()).assignedUserId).toBeNull();
});
test("a human comment is attributed, displayed as text and safely deduplicated on replay", async ({ page, workspace }, info) => {
    const incident = await requireWrites(workspace, info); await workspace.goto(`/incidents/${incident.id}`);
    const form = page.getByRole("form", { name: "Add incident comment" });
    await form.getByRole("button", { name: "Add comment", exact: true }).click();
    await expect(form.getByRole("alert")).toContainText("Write a comment");
    const content = `E2E investigation ${crypto.randomUUID()}\n<script>plain text evidence</script>`;
    await form.getByLabel("Investigation note", { exact: true }).fill(content);
    const pending = page.waitForResponse(response => new URL(response.url()).pathname.endsWith(`/${incident.id}/comments`) && response.request().method() === "POST");
    await form.getByRole("button", { name: "Add comment", exact: true }).click(); const response = await pending;
    expect(response.status()).toBe(201); const comment = incidentEventSchema.parse(await response.json());
    const key = await response.request().headerValue("Idempotency-Key"); expect(z.uuid().safeParse(key).success).toBe(true);
    expect(comment.actorUserId).toBe(workspace.organisation.currentUserId); expect(comment.details).toBe(content);
    await expect(page.getByRole("status").filter({ hasText: "Comment added" })).toBeVisible();
    const latest = page.getByRole("button", { name: "View latest events" }); if (await latest.isVisible()) await latest.click();
    await expect(page.getByText(content, { exact: true })).toBeVisible();
    expect(await page.locator("main script").count()).toBe(0);
    const replay = await workspace.api("POST", `/api/organisations/${workspace.organisation.id}/incidents/${incident.id}/comments`, { content }, key!);
    expect(replay.status).toBe(201); expect(incidentEventSchema.parse(replay.body).id).toBe(comment.id);
    const after = await workspace.incident(); expect(after.version).toBe(incident.version);
    expect((await timeline(workspace, incident.id, true)).content.filter(event => event.id === comment.id)).toHaveLength(1);
});
test.describe("ENGINEER", () => {
    test.use({ identity: "engineer" });
    test("an actual ENGINEER can claim and release an unassigned ongoing incident", async ({ page, workspace }, info) => {
        expect(workspace.organisation.currentUserRole).toBe("ENGINEER"); const before = await requireWrites(workspace, info);
        if (!nextOperationalState[before.status]) blocked(info, "Engineer claim fixture must be an ongoing incident");
        if (before.assignedUserId !== null) blocked(info, "Engineer claim fixture must initially be unassigned");
        await workspace.goto(`/incidents/${before.id}`);
        for (const label of ["Assign to me", "Release my assignment"]) {
            await page.getByRole("button", { name: label, exact: true }).click();
            const pending = page.waitForResponse(response => new URL(response.url()).pathname.endsWith(`/${before.id}/assignee`) && response.request().method() === "PATCH");
            await page.getByRole("dialog").getByRole("button", { name: "Confirm change" }).click(); expect((await pending).status()).toBe(200);
            await expect(page.getByRole("button", { name: label === "Assign to me" ? "Release my assignment" : "Assign to me", exact: true })).toBeEnabled();
        }
        expect((await workspace.incident()).assignedUserId).toBeNull(); await expect(page.getByLabel("Local user ID", { exact: true })).toHaveCount(0);
    });
});
test.describe("VIEWER", () => {
    test.use({ identity: "viewer" });
    test("an actual VIEWER sees incident details and timeline with no mutation controls", async ({ page, workspace }) => {
        expect(workspace.organisation.currentUserRole).toBe("VIEWER"); const incident = await workspace.incident();
        await workspace.goto(`/incidents/${incident.id}`); await expect(page.getByRole("heading", { name: incident.title, exact: true })).toBeVisible();
        await expect(page.getByRole("region", { name: "Lifecycle", exact: true }).getByRole("button")).toHaveCount(0);
        await expect(page.getByLabel("Investigation note", { exact: true })).toHaveCount(0); await expect(page.getByLabel("Local user ID", { exact: true })).toHaveCount(0);
        await expect(page.getByRole("heading", { name: "Incident timeline", exact: true })).toBeVisible();
    });
    test("the backend rejects an actual VIEWER's direct mutation with HTTP 403", async ({ workspace }, info) => {
        expect(workspace.organisation.currentUserRole).toBe("VIEWER"); const before = await requireWrites(workspace, info);
        const response = await workspace.api("PATCH", `/api/organisations/${workspace.organisation.id}/incidents/${before.id}/status`, { status: "ACKNOWLEDGED", expectedVersion: before.version });
        expect(response.status).toBe(403); expect((await workspace.incident()).version).toBe(before.version);
    });
});
