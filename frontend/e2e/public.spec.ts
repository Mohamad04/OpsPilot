import { test, expect } from "@playwright/test";

test("unauthenticated isolated browser reaches the actual Keycloak hosted login", async ({ page }) => {
    await page.goto("/incidents");
    await page.waitForURL(url => url.origin === new URL(process.env.E2E_KEYCLOAK_URL ?? "http://localhost:8082").origin);
    const url = new URL(page.url());
    expect(url.pathname).toContain("/protocol/openid-connect/auth");
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("client_id")).toBe("opspilot-frontend");
    await expect(page.locator("#username")).toBeVisible(); await expect(page.locator("#password")).toBeVisible();
    // No screenshot or credential entry in this unauthenticated smoke check.
});
test("actual backend rejects an unauthenticated incident request", async ({ request }) => {
    const response = await request.get(`${process.env.E2E_API_URL ?? "http://localhost:8080"}/api/organisations/00000000-0000-4000-8000-000000000001/incidents`);
    expect(response.status()).toBe(401);
});
