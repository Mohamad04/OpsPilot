import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { waitFor } from "@testing-library/react";
import { getHealthHistory, getLatestHealth } from "../api/healthApi";
import { endSession, getAccessToken } from "../auth/auth";
import { recordedHealth, unknownHealth } from "./healthFixtures";

vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn() }));
const fetchMock = vi.fn<(url: string, options: RequestInit) => Promise<Response>>();
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
beforeEach(() => {
    vi.clearAllMocks(); vi.stubGlobal("fetch", fetchMock);
    vi.mocked(getAccessToken).mockResolvedValue("monitoring-test-token");
    fetchMock.mockResolvedValue(json(recordedHealth("alpha", "service")));
});
afterEach(() => vi.unstubAllGlobals());

test("latest and bounded history use the existing bearer client and cancellation signal", async () => {
    const controller = new AbortController();
    const latest = recordedHealth("alpha", "service");
    expect(await getLatestHealth("alpha", "service", controller.signal)).toEqual(latest);
    fetchMock.mockResolvedValue(json([latest.latestResult]));
    expect(await getHealthHistory("alpha", "service", 20, controller.signal)).toEqual([latest.latestResult]);
    const [[latestUrl, options], [historyUrl]] = fetchMock.mock.calls;
    expect(new URL(latestUrl).pathname).toBe("/api/organisations/alpha/monitored-services/service/health/latest");
    expect(new URL(historyUrl).search).toBe("?limit=20");
    expect(new Headers(options.headers).get("Authorization")).toBe("Bearer monitoring-test-token");
    expect(options.signal).toBe(controller.signal);
    expect(options.redirect).toBe("error");
});

test("UNKNOWN without a result is a valid response, not a network error", async () => {
    fetchMock.mockResolvedValue(json(unknownHealth("alpha", "service")));
    expect((await getLatestHealth("alpha", "service")).latestResult).toBeNull();
});

test.each([401, 403, 404, 500])("HTTP %i is preserved as an error without retrying or converting it to UNKNOWN", async (status) => {
    fetchMock.mockResolvedValue(json({}, status));
    await expect(getLatestHealth("alpha", "service")).rejects.toMatchObject({ status });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(endSession).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
});

test.each(["wrapper-tenant", "record-tenant", "record-service", "invalid-time", "false-up", "unsupported-status"])("invalid or mismatched %s fails closed", async (kind) => {
    const latest = recordedHealth("alpha", "service");
    if (kind === "wrapper-tenant") latest.organisationId = "beta";
    if (kind === "record-tenant") latest.latestResult!.organisationId = "beta";
    if (kind === "record-service") latest.latestResult!.monitoredServiceId = "foreign";
    if (kind === "invalid-time") latest.latestResult!.completedAt = "invalid";
    if (kind === "false-up") latest.latestResult!.httpStatusCode = 503;
    const body: unknown = kind === "unsupported-status" ? { ...latest, status: "HEALTHY" } : latest;
    fetchMock.mockResolvedValue(json(body));
    await expect(getLatestHealth("alpha", "service")).rejects.toMatchObject({ status: 502 });
});

test("history rejects foreign records and oversized responses", async () => {
    const record = recordedHealth("beta", "service").latestResult;
    fetchMock.mockResolvedValue(json([record]));
    await expect(getHealthHistory("alpha", "service")).rejects.toMatchObject({ status: 502 });
    fetchMock.mockResolvedValue(json(Array.from({ length: 21 }, () => recordedHealth("alpha", "service").latestResult)));
    await expect(getHealthHistory("alpha", "service")).rejects.toMatchObject({ status: 502 });
});

test.each([0, 101, -1, 1.5])("invalid history limit %s never sends a request", async (limit) => {
    await expect(getHealthHistory("alpha", "service", limit)).rejects.toThrow(RangeError);
    expect(fetchMock).not.toHaveBeenCalled();
});

test("fan-out is bounded to four requests and cancelled queued requests are never sent", async () => {
    const pending: Array<() => void> = [];
    fetchMock.mockImplementation((url) => new Promise((resolve) => {
        const id = new URL(url).pathname.split("/")[5];
        pending.push(() => resolve(json(unknownHealth("alpha", id))));
    }));
    const controllers = Array.from({ length: 5 }, () => new AbortController());
    const finished = Promise.allSettled(controllers.map((controller, index) => getLatestHealth("alpha", `s${index}`, controller.signal)));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
    controllers[4].abort();
    pending.forEach((resolve) => resolve());
    const results = await finished;
    expect(results.filter((result) => result.status === "fulfilled")).toHaveLength(4);
    expect(results[4].status).toBe("rejected");
    expect(fetchMock).toHaveBeenCalledTimes(4);
});

test("aborting an active request frees its slot without sending a stale tenant request", async () => {
    const pending: Array<() => void> = [];
    fetchMock.mockImplementation((url, options) => new Promise((resolve, reject) => {
        const id = new URL(url).pathname.split("/")[5];
        options.signal?.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
        pending.push(() => resolve(json(unknownHealth("alpha", id))));
    }));
    const controllers = Array.from({ length: 5 }, () => new AbortController());
    const finished = Promise.allSettled(controllers.map((controller, index) => getLatestHealth("alpha", `s${index}`, controller.signal)));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
    controllers[0].abort();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(5));
    pending.forEach((resolve) => resolve());
    const results = await finished;
    expect(results[0].status).toBe("rejected");
    expect(results.filter((result) => result.status === "fulfilled")).toHaveLength(4);
});
