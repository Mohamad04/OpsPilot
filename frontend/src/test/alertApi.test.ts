import { beforeEach, afterEach, expect, test, vi } from "vitest";
import { getAlert, getAlerts, getAlertSummary } from "../api/alertApi";
import { getAlertRule, putAlertRule } from "../api/alertRuleApi";
import { getAccessToken, endSession } from "../auth/auth";
import { alertFixture, alertPage, defaultAlertRule, emptyAlertSummary } from "./alertFixtures";

vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn() }));
const fetchMock = vi.fn<(url: string, options: RequestInit) => Promise<Response>>();
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
beforeEach(() => {
    vi.clearAllMocks(); vi.stubGlobal("fetch", fetchMock);
    vi.mocked(getAccessToken).mockResolvedValue("alert-test-token");
});
afterEach(() => vi.unstubAllGlobals());

test("paginated filters use encoded parameters, bearer authentication and cancellation", async () => {
    const controller = new AbortController();
    fetchMock.mockResolvedValue(json(alertPage([], 2, 10, 25)));
    await getAlerts("alpha", { page: 2, size: 10, status: "ACTIVE", severity: "HIGH", serviceId: "svc/a",
        from: "2026-10-01T00:00:00+01:00", to: "2026-11-01T00:00:00Z" }, controller.signal);
    const [url, options] = fetchMock.mock.calls[0]; const parsed = new URL(url);
    expect(parsed.pathname).toBe("/api/organisations/alpha/alerts");
    expect(Object.fromEntries(parsed.searchParams)).toEqual({ page: "2", size: "10", status: "ACTIVE", severity: "HIGH",
        serviceId: "svc/a", from: "2026-10-01T00:00:00+01:00", to: "2026-11-01T00:00:00Z" });
    expect(new Headers(options.headers).get("Authorization")).toBe("Bearer alert-test-token");
    expect(options.signal).toBe(controller.signal);
});
test("default list and bounded service history use the committed endpoints", async () => {
    fetchMock.mockResolvedValue(json(alertPage())); await getAlerts("alpha");
    expect(new URL(fetchMock.mock.calls[0][0]).search).toBe("?page=0&size=20");
    fetchMock.mockResolvedValue(json(alertPage([alertFixture()], 0, 5)));
    await getAlerts("alpha", { size: 5 }, undefined, "service");
    expect(new URL(fetchMock.mock.calls[1][0]).pathname).toBe("/api/organisations/alpha/monitored-services/service/alerts");
});
test.each(["ACTIVE", "RESOLVED"] as const)("details validate %s and discard extra diagnostic fields", async (status) => {
    const alert = alertFixture("alpha", "service", status);
    fetchMock.mockResolvedValue(json({ ...alert, triggeringFailureDetail: "private diagnostic" }));
    expect(await getAlert("alpha", alert.id)).toEqual(alert);
});
test.each([401, 403, 404, 500])("HTTP %i is preserved without redirect loops or retries", async (status) => {
    fetchMock.mockResolvedValue(json({}, status));
    await expect(getAlert("alpha", "id")).rejects.toMatchObject({ status });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(endSession).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
});
test.each([
    { organisationId: "beta" }, { id: "foreign" }, { firstSeenAt: "invalid" }, { status: "UNKNOWN" },
    { severity: "URGENT" }, { occurrenceCount: -1 }, { lastSeenAt: "2025-01-01T00:00:00Z" },
    { resolvedAt: "2026-10-27T10:03:00Z" }, { triggeringResponseTimeMs: -1 }, { failureThreshold: 21 },
])("invalid or foreign alert details fail closed: %j", async (change) => {
    fetchMock.mockResolvedValue(json({ ...alertFixture(), ...change }));
    await expect(getAlert("alpha", "alert-1")).rejects.toMatchObject({ status: 502 });
});
test("list rejects foreign records, wrong filters, duplicated rows and incorrect pagination metadata", async () => {
    for (const data of [alertPage([alertFixture("beta")]), alertPage([alertFixture()], 1),
        alertPage([alertFixture(), alertFixture()]), { ...alertPage(), totalPages: 3 }]) {
        fetchMock.mockResolvedValue(json(data));
        await expect(getAlerts("alpha")).rejects.toMatchObject({ status: 502 });
    }
    fetchMock.mockResolvedValue(json(alertPage([alertFixture()])));
    await expect(getAlerts("alpha", { status: "RESOLVED" })).rejects.toMatchObject({ status: 502 });
    await expect(getAlerts("alpha", { severity: "LOW" })).rejects.toMatchObject({ status: 502 });
    await expect(getAlerts("alpha", { serviceId: "foreign" })).rejects.toMatchObject({ status: 502 });
    await expect(getAlerts("alpha", { from: "2026-10-28T00:00:00Z" })).rejects.toMatchObject({ status: 502 });
    await expect(getAlerts("alpha", { to: "2026-10-27T10:00:00Z" })).rejects.toMatchObject({ status: 502 });
    await expect(getAlerts("alpha", {}, undefined, "foreign")).rejects.toMatchObject({ status: 502 });
});
test.each([{ size: 101 }, { size: 0 }, { page: -1 }, { page: 2147483647, size: 100 },
    { from: "bad" }, { from: "2026-11-01T00:00:00Z", to: "2026-10-01T00:00:00Z" }])("invalid filters never request data: %j", async (filters) => {
    await expect(getAlerts("alpha", filters)).rejects.toThrow(); expect(fetchMock).not.toHaveBeenCalled();
});
test("summary verifies counts and includes all four severities", async () => {
    fetchMock.mockResolvedValue(json(emptyAlertSummary)); expect(await getAlertSummary("alpha")).toEqual(emptyAlertSummary);
    expect(new URL(fetchMock.mock.calls[0][0]).pathname).toBe("/api/organisations/alpha/alerts/summary");
    fetchMock.mockResolvedValue(json({ ...emptyAlertSummary, activeAlerts: 1 }));
    await expect(getAlertSummary("alpha")).rejects.toMatchObject({ status: 502 });
});
test("rule GET accepts effective defaults with null identity and timestamps", async () => {
    const rule = defaultAlertRule("alpha", "service"); fetchMock.mockResolvedValue(json(rule));
    expect(await getAlertRule("alpha", "service")).toEqual(rule);
    expect(new URL(fetchMock.mock.calls[0][0]).pathname).toBe("/api/organisations/alpha/monitored-services/service/alert-rule");
});
test("rule PUT validates the payload and verifies the confirmed persisted response", async () => {
    const values = { enabled: false, consecutiveFailures: 5, consecutiveRecoveries: 4, severity: "LOW" as const };
    const saved = { ...defaultAlertRule("alpha", "service"), ...values, id: "rule", createdAt: "2026-10-27T10:00:00Z", updatedAt: "2026-10-27T10:00:00Z" };
    fetchMock.mockResolvedValue(json(saved)); expect(await putAlertRule("alpha", "service", values)).toEqual(saved);
    const options = fetchMock.mock.calls[0][1]; expect(options.method).toBe("PUT"); expect(JSON.parse(String(options.body))).toEqual(values);
    expect(new Headers(options.headers).get("Content-Type")).toBe("application/json");
    fetchMock.mockResolvedValue(json({ ...saved, consecutiveFailures: 8 }));
    await expect(putAlertRule("alpha", "service", values)).rejects.toMatchObject({ status: 502 });
});
test.each([0, 21, 1.5, NaN])("invalid threshold %s prevents saving", async (consecutiveFailures) => {
    await expect(putAlertRule("alpha", "service", { enabled: true, consecutiveFailures, consecutiveRecoveries: 2, severity: "HIGH" })).rejects.toThrow();
    expect(fetchMock).not.toHaveBeenCalled();
});
test("rule identity and lifecycle metadata are verified, including default values", async () => {
    for (const change of [{ organisationId: "beta" }, { monitoredServiceId: "foreign" }, { consecutiveFailures: 1 }, { id: "saved" }]) {
        fetchMock.mockResolvedValue(json({ ...defaultAlertRule("alpha", "service"), ...change }));
        await expect(getAlertRule("alpha", "service")).rejects.toMatchObject({ status: 502 });
    }
});
test("malformed JSON is a controlled validation error", async () => {
    fetchMock.mockResolvedValue(new Response("not-json")); await expect(getAlertSummary("alpha")).rejects.toMatchObject({ status: 502 });
});
