import { defineConfig, devices } from "@playwright/test";

const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:5173";
for (const value of [baseURL, process.env.E2E_API_URL ?? "http://localhost:8080", process.env.E2E_KEYCLOAK_URL ?? "http://localhost:8082"]) {
    const url = new URL(value);
    if (!["localhost", "127.0.0.1", "[::1]"].includes(url.hostname) || !["http:", "https:"].includes(url.protocol) || url.username || url.password) {
        throw new Error("E2E URLs must target local development services without embedded credentials.");
    }
}
export default defineConfig({
    testDir: "./e2e", testMatch: "**/*.spec.ts", outputDir: "./test-results", timeout: 60_000,
    expect: { timeout: 10_000 }, fullyParallel: false, workers: 1, retries: 0,
    reporter: [["./e2e/reporter.ts"]],
    // Authentication and API traffic contain secrets: never record HAR/video/traces or storageState.
    use: { baseURL, trace: "off", screenshot: "off", video: "off", serviceWorkers: "block" },
    projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
    // Use the actual running app. Never start/restart backend/infrastructure or inject mock responses.
});
