import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import { test, expect, blocked, noOverflow } from "./fixtures";

for (const width of [360, 390, 768, 1024, 1280, 1440]) {
    test(`incident registry and navigation at ${width}px`, async ({ page, workspace }) => {
        await page.setViewportSize({ width, height: 1000 }); await workspace.goto("/incidents");
        await expect(page.getByRole("region", { name: "Incident registry" })).toHaveAttribute("aria-busy", "false");
        await noOverflow(page);
        for (const name of ["Status", "Severity", "Affected service", "Created"]) {
            const control = page.getByLabel(name, { exact: true }); await expect(control).toBeVisible();
            const box = await control.boundingBox(); expect(box!.width).toBeGreaterThan(40);
            expect(box!.x).toBeGreaterThanOrEqual(0); expect(box!.x + box!.width).toBeLessThanOrEqual(width + 1);
            await control.focus(); await expect(control).toBeFocused();
        }
        const trigger = page.getByRole("button", { name: "Open navigation", exact: true });
        if (await trigger.isVisible()) {
            await trigger.click(); const drawer = page.getByRole("dialog", { name: "Navigation", exact: true });
            await expect(drawer.getByRole("button", { name: "Close navigation" })).toBeFocused();
            await expect(drawer.getByRole("link", { name: "Incidents", exact: true })).toBeVisible();
            await page.keyboard.press("Escape"); await expect(drawer).not.toBeVisible(); await expect(trigger).toBeFocused();
        } else await expect(page.getByRole("navigation", { name: "Main navigation" }).getByRole("link", { name: "Incidents", exact: true })).toBeVisible();
        await noOverflow(page);
    });
    test(`incident details, timeline, forms and dialog at ${width}px`, async ({ page, workspace }) => {
        const incident = await workspace.incident(); await page.setViewportSize({ width, height: 1000 });
        await workspace.goto(`/incidents/${incident.id}`);
        await expect(page.getByRole("heading", { name: incident.title, exact: true })).toBeVisible();
        const timeline = page.getByRole("region", { name: "Incident timeline", exact: true });
        await expect(timeline).toHaveAttribute("aria-busy", "false"); await noOverflow(page);
        const composer = page.getByLabel("Investigation note", { exact: true });
        if (workspace.organisation.currentUserRole !== "VIEWER") {
            await composer.fill(`E2E local unsent layout probe ${"long-unbroken-text".repeat(100)}`);
            await expect(composer).toHaveAttribute("maxlength", "4000"); await composer.focus(); await expect(composer).toBeFocused();
            await noOverflow(page);
            const button = page.getByRole("region", { name: "Lifecycle", exact: true }).getByRole("button").first();
            await button.click(); const dialog = page.getByRole("dialog", { name: "Change incident status?" });
            await expect(dialog).toBeVisible(); await expect(dialog.getByRole("button", { name: "Cancel", exact: true })).toBeFocused();
            for (let index = 0; index < 4; index++) { await page.keyboard.press("Tab");
                expect(await dialog.evaluate(element => element.contains(document.activeElement))).toBe(true); }
            const bounds = await dialog.boundingBox(); expect(bounds!.x).toBeGreaterThanOrEqual(0); expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width + 1);
            await page.keyboard.press("Escape"); await expect(dialog).not.toBeVisible(); await expect(button).toBeFocused();
            await page.getByRole("button", { name: "Discard draft", exact: true }).click();
        } else await expect(composer).toHaveCount(0);
        await noOverflow(page);
    });
}
test("capture genuine clean demonstration screens at a consistent desktop viewport", async ({ page, workspace }, info) => {
    if (process.env.E2E_SCREENSHOTS !== "1" || process.env.E2E_DATA_IS_DEMO !== "1") blocked(info, "Recruiter captures require E2E_SCREENSHOTS=1 and E2E_DATA_IS_DEMO=1");
    if (!workspace.organisation.slug.startsWith("opspilot-e2e-")) throw new Error("Screenshot organisation must be a dedicated opspilot-e2e- demo");
    if (workspace.organisation.currentUserRole === "VIEWER") blocked(info, "Lifecycle screenshot requires an operational membership");
    const incident = await workspace.incident();
    await page.setViewportSize({ width: 1440, height: 1000 });
    const destination = resolve("../docs/assets/screenshots"); await mkdir(destination, { recursive: true });
    async function capture(name: string) {
        await noOverflow(page);
        await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);
        // Only app content. The existing header/identity/email and all credential pages are excluded.
        await page.getByRole("main").screenshot({ path: resolve(destination, `${name}.png`), animations: "disabled", mask: [page.locator("main input, main textarea")] });
    }
    await workspace.goto("/"); await expect(page.getByRole("heading", { name: "Dashboard", exact: true })).toBeVisible();
    await expect(page.getByRole("region", { name: "HTTP monitoring overview", exact: true })).toHaveAttribute("aria-busy", "false");
    await expect(page.getByText("Loading monitoring observations…")).toHaveCount(0); await capture("monitoring-dashboard");
    await workspace.goto("/incidents"); await expect(page.getByRole("region", { name: "Incident registry" })).toHaveAttribute("aria-busy", "false"); await capture("incident-list");
    await workspace.goto(`/incidents/${incident.id}`); await expect(page.getByRole("heading", { name: incident.title, exact: true })).toBeVisible();
    await expect(page.getByRole("region", { name: "Incident timeline", exact: true })).toHaveAttribute("aria-busy", "false"); await capture("incident-details-timeline");
    await page.getByRole("region", { name: "Lifecycle", exact: true }).getByRole("button").first().click();
    await expect(page.getByRole("dialog")).toBeVisible();
    // A modal is in the top layer, so capture only that dialog (no surrounding private browser content).
    await page.getByRole("dialog").screenshot({ path: resolve(destination, "incident-lifecycle-confirmation.png"), animations: "disabled" });
    await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click();
});
