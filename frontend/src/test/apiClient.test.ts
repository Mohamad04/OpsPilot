import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { apiFetch } from "../api/client";
import { provisionCurrentUser } from "../api/meApi";
import { endSession, getAccessToken } from "../auth/auth";
import { createOrganisation, getAllOrganisations, getOrganisation } from "../api/organisationApi";
import { createMonitoredService, disableMonitoredService, enableMonitoredService, getMonitoredService, getMonitoredServices } from "../api/monitoredServiceApi";

vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn() }));
const fetchMock = vi.fn();

beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("fetch", fetchMock);
    vi.mocked(getAccessToken).mockResolvedValue("test-token");
    fetchMock.mockResolvedValue(new Response("[]"));
});
afterEach(() => vi.unstubAllGlobals());

test("preserves headers, body, method and cancellation while adding a fresh bearer token", async () => {
    const controller = new AbortController();
    await apiFetch("http://localhost:8080/api/organisations", {
        method: "POST", headers: { "Content-Type": "application/json", "X-Request-Id": "123" },
        body: '{"name":"Acme"}', signal: controller.signal,
    });
    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers.get("Authorization")).toBe("Bearer test-token");
    expect(options.headers.get("Content-Type")).toBe("application/json");
    expect(options.headers.get("X-Request-Id")).toBe("123");
    expect(options).toMatchObject({ method: "POST", body: '{"name":"Acme"}', signal: controller.signal, redirect: "error" });
});

test.each([
    () => provisionCurrentUser(),
    () => getAllOrganisations(),
    () => getOrganisation("org"),
    () => createOrganisation({ name: "Acme", slug: "acme" }),
    () => getMonitoredServices("org"),
    () => getMonitoredService("org", "service"),
    () => enableMonitoredService("org", "service"),
    () => disableMonitoredService("org", "service"),
    () => createMonitoredService("org", { name: "API", serviceType: "HTTP", environment: "PRODUCTION", baseUrl: "https://example.com", healthEndpoint: "/health", owner: "Team" }),
])("existing API operation %$ uses the centralized bearer client", async (operation) => {
    await operation();
    expect(getAccessToken).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1].headers.get("Authorization")).toBe("Bearer test-token");
});

test("401 ends the session once without replaying mutations or redirecting", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
    await expect(createOrganisation({ name: "Acme", slug: "acme" })).rejects.toMatchObject({ status: 401 });
    expect(endSession).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
});

test("403 preserves the authenticated session and reports a permission error", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 403 }));
    await expect(getAllOrganisations()).rejects.toMatchObject({ status: 403, message: expect.stringContaining("permission") });
    expect(endSession).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
});

test("refresh failures prevent the backend request", async () => {
    vi.mocked(getAccessToken).mockRejectedValue(new Error("Session expired"));
    await expect(getAllOrganisations()).rejects.toThrow("Session expired");
    expect(fetchMock).not.toHaveBeenCalled();
});

test("other response failures retain the existing API-specific error", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 500 }));
    await expect(getAllOrganisations()).rejects.toThrow("Could not get organisations.");
});

test("refuses to send a bearer token outside the backend API", async () => {
    await expect(apiFetch("https://other.example/api/organisations")).rejects.toThrow("Invalid backend API URL");
    expect(fetchMock).not.toHaveBeenCalled();
    expect(getAccessToken).not.toHaveBeenCalled();
});

test("provisioning sends only a bodyless authenticated POST without frontend identity or roles", async () => {
    await provisionCurrentUser();
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith("http://localhost:8080/api/me/provision", expect.objectContaining({ method: "POST" }));
    expect(fetchMock.mock.calls[0][1].body).toBeUndefined();
});

test.each([
    [403, "Verify your Keycloak email"],
    [409, "account conflict"],
    [500, "Please retry or contact an administrator"],
])("provisioning HTTP %i exposes a recoverable error without ending the Keycloak session", async (status, message) => {
    fetchMock.mockResolvedValue(new Response(null, { status }));
    await expect(provisionCurrentUser()).rejects.toThrow(message);
    expect(endSession).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
});

test("provisioning 401 retains the existing session rejection behavior", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
    await expect(provisionCurrentUser()).rejects.toMatchObject({ status: 401 });
    expect(endSession).toHaveBeenCalledTimes(1);
});

test("provisioning network failures produce a safe backend-availability message", async () => {
    fetchMock.mockRejectedValue(new TypeError("Network failure"));
    await expect(provisionCurrentUser()).rejects.toThrow("Check that the backend is available");
});
