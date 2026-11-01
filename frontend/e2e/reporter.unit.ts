import { afterEach, expect, test, vi } from "vitest";
import { writeFileSync } from "node:fs";
import type { TestCase, TestResult } from "@playwright/test/reporter";
import SafeReporter from "./reporter";

const files = vi.hoisted(() => ({ mkdirSync: vi.fn(), writeFileSync: vi.fn() }));
vi.mock("node:fs", () => ({ ...files, default: files }));
afterEach(() => { vi.restoreAllMocks(); vi.clearAllMocks(); vi.unstubAllEnvs(); });
const fixture = (annotations: TestCase["annotations"] = []) => ({ title: "Dedicated E2E scenario", annotations,
    location: { file: "C:/projects/OpsPilot/frontend/e2e/incidents.spec.ts", line: 10 } }) as TestCase;

test("failure reports retain useful metadata without serializing auth errors, DOM or step arguments", async () => {
    const log = vi.spyOn(console, "log").mockImplementation(() => {});
    const reporter = new SafeReporter();
    const result = { status: "failed", duration: 12, errors: [{ message: "password=secret-sentinel Authorization: Bearer token-sentinel" }],
        attachments: [], steps: [{ title: "Fill secret-sentinel" }] } as unknown as TestResult;
    reporter.onTestEnd(fixture(), result); await reporter.onEnd({ status: "failed", duration: 12, startTime: new Date(0) });
    const json = String(vi.mocked(writeFileSync).mock.calls[0][1]);
    expect(json).toContain('"errorCount": 1'); expect(json).toContain("incidents.spec.ts");
    expect(json).not.toContain("secret-sentinel"); expect(json).not.toContain("token-sentinel");
    expect(JSON.stringify(log.mock.calls)).not.toContain("secret-sentinel");
});
test("missing prerequisites stay blocked and the strict acceptance gate returns failure", async () => {
    vi.spyOn(console, "log").mockImplementation(() => {}); vi.stubEnv("E2E_REQUIRE_COMPLETE", "1");
    const reporter = new SafeReporter();
    reporter.onTestEnd(fixture([{ type: "blocked", description: "Missing dedicated test credentials" }]),
        { status: "skipped", duration: 0, errors: [], attachments: [] } as unknown as TestResult);
    await expect(reporter.onEnd({ status: "passed", duration: 1, startTime: new Date(0) })).resolves.toEqual({ status: "failed" });
    const json = JSON.parse(String(vi.mocked(writeFileSync).mock.calls[0][1]));
    expect(json).toMatchObject({ status: "failed", acceptanceComplete: false, counts: { blocked: 1 } });
    expect(json.results[0].status).toBe("blocked");
});
