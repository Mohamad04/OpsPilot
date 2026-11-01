import { expect, test } from "vitest";
import { inspectE2EEnvironment } from "./environment";

const complete = () => ({ E2E_USERNAME: "dedicated-owner", E2E_PASSWORD: "owner-secret-sentinel",
    E2E_ENGINEER_USERNAME: "dedicated-engineer", E2E_ENGINEER_PASSWORD: "engineer-secret-sentinel",
    E2E_VIEWER_USERNAME: "dedicated-viewer", E2E_VIEWER_PASSWORD: "viewer-secret-sentinel",
    E2E_ORGANISATION_ID: "11111111-1111-4111-8111-111111111111", E2E_INCIDENT_ID: "22222222-2222-4222-8222-222222222222",
    E2E_SECOND_ORGANISATION_ID: "33333333-3333-4333-8333-333333333333", E2E_ASSIGNEE_USER_ID: "44444444-4444-4444-8444-444444444444",
    E2E_ALLOW_WRITES: "1", E2E_DATA_IS_DEMO: "1", E2E_SCREENSHOTS: "1" });
test("missing prerequisites are named without inventing credentials or resource IDs", () => {
    const result = inspectE2EEnvironment({});
    expect(result.configurationComplete).toBe(false); expect(result.liveVerificationPerformed).toBe(false);
    expect(result.checks.filter(item => item.status === "missing")).toHaveLength(10);
    expect(result.checks.find(item => item.name === "E2E_ALLOW_WRITES")?.status).toBe("disabled");
});
test("complete local format never claims credentials or fixtures were authenticated", () => {
    expect(inspectE2EEnvironment(complete())).toMatchObject({ configurationComplete: true, liveVerificationPerformed: false });
});
test("reports contain neither passwords nor username values, including invalid URL diagnostics", () => {
    const result = inspectE2EEnvironment({ ...complete(), E2E_API_URL: "http://dedicated-owner:embedded-secret@outside.example.test" });
    const text = JSON.stringify(result);
    for (const secret of ["dedicated-owner", "owner-secret-sentinel", "engineer-secret-sentinel", "viewer-secret-sentinel", "embedded-secret"]) expect(text).not.toContain(secret);
    expect(result.checks.find(item => item.name === "E2E_API_URL")?.status).toBe("invalid");
});
test("malformed UUIDs and reused tenant scope cannot satisfy isolation prerequisites", () => {
    const values = complete();
    const result = inspectE2EEnvironment({ ...values, E2E_ASSIGNEE_USER_ID: "not-a-local-uuid", E2E_SECOND_ORGANISATION_ID: values.E2E_ORGANISATION_ID });
    expect(result.configurationComplete).toBe(false);
    expect(result.checks.filter(item => item.status === "invalid").map(item => item.name)).toEqual(["E2E_SECOND_ORGANISATION_ID", "E2E_ASSIGNEE_USER_ID"]);
});
test("role identities must be distinct and unsafe origins are rejected", () => {
    const values = complete();
    const result = inspectE2EEnvironment({ ...values, E2E_VIEWER_USERNAME: values.E2E_USERNAME.toUpperCase(), E2E_BASE_URL: "https://outside.example.test" });
    expect(result.checks.find(item => item.name === "E2E_USERNAME")?.status).toBe("invalid");
    expect(result.checks.find(item => item.name === "E2E_VIEWER_USERNAME")?.status).toBe("invalid");
    expect(result.checks.find(item => item.name === "E2E_BASE_URL")?.status).toBe("invalid");
});
test("write and screenshot consent cannot be silently inferred from non-boolean flags", () => {
    const result = inspectE2EEnvironment({ ...complete(), E2E_ALLOW_WRITES: "true", E2E_SCREENSHOTS: "0" });
    expect(result.configurationComplete).toBe(false);
    expect(result.checks.find(item => item.name === "E2E_ALLOW_WRITES")?.status).toBe("invalid");
    expect(result.checks.find(item => item.name === "E2E_SCREENSHOTS")?.status).toBe("disabled");
});
