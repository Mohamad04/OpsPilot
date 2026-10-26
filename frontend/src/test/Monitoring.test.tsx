import { StrictMode } from "react";
import { act, render, renderHook, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { focusManager, keepPreviousData, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import App from "../App";
import { OrganisationProvider } from "../context/OrganisationProvider";
import { healthKeys, useLatestHealth } from "../hooks/useMonitoring";
import { getAccessToken, endSession } from "../auth/auth";
import type { MonitoredService } from "../types/MonitoredService";
import { recordedHealth, unknownHealth } from "./healthFixtures";

// Real routes, shell, organisation provider, monitoring APIs and cache; controlled HTTP/auth fixtures.
vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn(), logout: vi.fn() }));
vi.mock("../auth/useAuth", () => ({ useAuth: () => ({ status: "authenticated", user: { displayName: "Monitoring tester" } }) }));
const alpha = { id: "alpha", name: "Alpha team", slug: "alpha", status: "ACTIVE", createdAt: "2026-10-26" };
const beta = { ...alpha, id: "beta", name: "Beta team", slug: "beta" };
const service: MonitoredService = {
    id: "alpha-up", organisationId: alpha.id, name: "Alpha API", owner: "Alpha team", serviceType: "HTTP",
    environment: "PRODUCTION", enabled: true, baseUrl: "https://alpha.example.test", healthEndpoint: "/health",
};
const alphaServices: MonitoredService[] = [service,
    { ...service, id: "alpha-down", name: "Alpha worker" },
    { ...service, id: "alpha-unknown", name: "Unobserved service" },
    { ...service, id: "alpha-paused", name: "Paused service", enabled: false },
    { ...service, id: "alpha-tcp", name: "TCP service", serviceType: "TCP" },
];
const betaService: MonitoredService = { ...service, id: "beta-down", organisationId: beta.id, name: "Beta API", baseUrl: "https://beta.example.test" };
const collection = (org: string) => `/api/organisations/${org}/monitored-services`;
const health = (org: string, id: string, kind = "latest") => `${collection(org)}/${id}/health/${kind}`;
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const fetchMock = vi.fn<(url: string, options: RequestInit) => Promise<Response>>();
const clients: QueryClient[] = [];
let handlers: Record<string, () => Promise<Response>>;

beforeEach(() => {
    vi.clearAllMocks(); vi.stubGlobal("fetch", fetchMock);
    vi.mocked(getAccessToken).mockResolvedValue("monitoring-integration-token");
    handlers = {};
    fetchMock.mockImplementation(async (url, options) => {
        expect(new Headers(options.headers).get("Authorization")).toBe("Bearer monitoring-integration-token");
        const path = new URL(url).pathname;
        if (handlers[path]) return (await handlers[path]()).clone();
        if (path === "/api/organisations") return json([alpha, beta]);
        const parts = path.match(/^\/api\/organisations\/([^/]+)\/monitored-services(?:\/([^/]+))?(?:\/health\/(latest|history))?$/);
        if (!parts) throw new Error(`Unexpected monitoring API request: ${path}`);
        const [, org, id, kind] = parts;
        const services = org === alpha.id ? alphaServices : [betaService];
        if (!id) return json(services);
        const found = services.find((item) => item.id === id);
        if (!found) return json({}, 404);
        if (!kind) return json(found);
        const result = id.includes("unknown") ? unknownHealth(org, id)
            : recordedHealth(org, id, id.includes("down") ? "DOWN" : "UP", found.enabled);
        return json(kind === "latest" ? result : result.latestResult ? [result.latestResult] : []);
    });
});

afterEach(() => { clients.splice(0).forEach((client) => client.clear()); focusManager.setFocused(undefined); vi.useRealTimers(); vi.unstubAllGlobals(); });

function mount(path = "/", client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })) {
    clients.push(client);
    render(<StrictMode><QueryClientProvider client={client}><OrganisationProvider>
        <MemoryRouter initialEntries={[path]}><App /></MemoryRouter>
    </OrganisationProvider></QueryClientProvider></StrictMode>);
    return client;
}
function deferred() {
    let resolve!: (value: Response) => void;
    const promise = new Promise<Response>((complete) => { resolve = complete; });
    return { promise, resolve };
}
const latestCard = () => screen.getByRole("region", { name: "Latest health check" });
const historyCard = () => screen.getByRole("region", { name: "Recent health checks" });
const healthCalls = (org: string, id: string, kind = "latest") => fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === health(org, id, kind));

test("dashboard counts actual latest statuses and excludes disabled and unsupported services", async () => {
    mount();
    const up = await screen.findByRole("group", { name: "Latest UP" }, { timeout: 3000 });
    expect(up).toHaveTextContent("UP1");
    expect(screen.getByRole("group", { name: "Latest DOWN" })).toHaveTextContent("DOWN1");
    expect(screen.getByRole("group", { name: "Latest UNKNOWN" })).toHaveTextContent("UNKNOWN1");
    expect(screen.getByText(/1 paused service/)).toBeInTheDocument();
    expect(healthCalls(alpha.id, "alpha-paused")).toHaveLength(0);
    expect(healthCalls(alpha.id, "alpha-tcp")).toHaveLength(0);
    expect(screen.getAllByText("Not implemented")).toHaveLength(3);
    expect(screen.queryByText(/\d+\s*%/)).not.toBeInTheDocument();
});

test("dashboard waits for every observation and does not present incomplete counts on failure", async () => {
    const pending = deferred();
    handlers[health(alpha.id, "alpha-down")] = () => pending.promise;
    mount();
    await screen.findByText("Loading monitoring observations…");
    expect(screen.queryByRole("group", { name: "Latest UP" })).not.toBeInTheDocument();
    await act(async () => pending.resolve(json({}, 500)));
    expect(await screen.findByRole("alert")).toHaveTextContent("Health totals are hidden");
    expect(screen.queryByRole("group", { name: "Latest UP" })).not.toBeInTheDocument();
    delete handlers[health(alpha.id, "alpha-down")];
    await userEvent.click(screen.getByRole("button", { name: "Retry monitoring" }));
    await screen.findByRole("group", { name: "Latest UP" });
});

test("registry shows observed health separately from configuration and labels paused historical results", async () => {
    mount("/services");
    const row = (name: string) => screen.getByRole("link", { name }).closest("tr")!;
    await screen.findByRole("link", { name: "Alpha API" });
    expect(await within(row("Alpha API")).findByText("UP")).toBeInTheDocument();
    expect(within(row("Alpha API")).getByText("Enabled")).toBeInTheDocument();
    expect(await within(row("Alpha worker")).findByText("DOWN")).toBeInTheDocument();
    expect(await within(row("Unobserved service")).findByText("UNKNOWN")).toBeInTheDocument();
    expect(await within(row("Paused service")).findByText("Paused")).toBeInTheDocument();
    expect(within(row("Paused service")).queryByText("UP", { exact: true })).not.toBeInTheDocument();
    expect(within(row("Paused service")).getByText(/Historical UP/)).toBeInTheDocument();
    expect(within(row("TCP service")).getByText("HTTP monitoring only")).toBeInTheDocument();
    expect(healthCalls(alpha.id, "alpha-tcp")).toHaveLength(0);
});

test("details shows real status, HTTP code, duration, timestamp and bounded history", async () => {
    mount("/services/alpha-up");
    await screen.findByRole("heading", { name: "Alpha API" });
    expect(await within(latestCard()).findByText("UP")).toBeInTheDocument();
    expect(within(latestCard()).getByText("200")).toBeInTheDocument();
    expect(within(latestCard()).getByText("25 ms")).toBeInTheDocument();
    expect(latestCard().querySelector("time")).toHaveAttribute("dateTime", "2026-10-26T12:00:00.025Z");
    expect(await within(historyCard()).findByRole("list")).toBeInTheDocument();
    expect(within(historyCard()).getAllByRole("listitem")).toHaveLength(1);
    expect(new URL(healthCalls(alpha.id, service.id, "history")[0][0]).search).toBe("?limit=20");
});

test("UNKNOWN and empty history are meaningful states without fabricated response values", async () => {
    mount("/services/alpha-unknown");
    await screen.findByText("No health-check history yet.");
    expect(within(latestCard()).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(latestCard()).getByText("No health check has been recorded yet.")).toBeInTheDocument();
    expect(within(latestCard()).queryByText(/\d+ ms/)).not.toBeInTheDocument();
});

test("connection/timeout DOWN reports no HTTP response and the actual sanitised failure detail", async () => {
    const down = recordedHealth(alpha.id, service.id, "DOWN");
    down.latestResult!.httpStatusCode = null;
    down.latestResult!.failureDetail = "HTTP check timed out.";
    handlers[health(alpha.id, service.id)] = async () => json(down);
    mount("/services/alpha-up");
    await screen.findByRole("heading", { name: "Alpha API" });
    expect(await within(latestCard()).findByText("DOWN")).toBeInTheDocument();
    expect(within(latestCard()).getByText("No HTTP response")).toBeInTheDocument();
    expect(within(latestCard()).getByText("HTTP check timed out.")).toBeInTheDocument();
});

test("disabled service details never present a historical UP result as current health", async () => {
    mount("/services/alpha-paused");
    await screen.findByText("Last observed health: UP (historical).");
    expect(within(latestCard()).getByText("Paused")).toBeInTheDocument();
    expect(within(latestCard()).queryByText("UP", { exact: true })).not.toBeInTheDocument();
    expect(within(latestCard()).getByText(/Monitoring is disabled/)).toBeInTheDocument();
});

test("latest and history loading are independent; history errors can be retried", async () => {
    const pending = deferred();
    handlers[health(alpha.id, service.id, "history")] = () => pending.promise;
    mount("/services/alpha-up");
    await screen.findByRole("heading", { name: "Alpha API" });
    await within(latestCard()).findByText("UP");
    expect(within(historyCard()).getByText("Loading health history…")).toBeInTheDocument();
    await act(async () => pending.resolve(json({}, 500)));
    expect(await within(historyCard()).findByRole("alert")).toHaveTextContent("Could not load health-check history");
    delete handlers[health(alpha.id, service.id, "history")];
    await userEvent.click(screen.getByRole("button", { name: "Retry history" }));
    await within(historyCard()).findByRole("list");
});

test.each([401, 403, 404])("access-denied refresh (%i) hides both cached latest and history without retry loops", async (status) => {
    const client = mount("/services/alpha-up");
    await screen.findByRole("heading", { name: "Alpha API" });
    await within(latestCard()).findByText("UP");
    await within(historyCard()).findByRole("list");
    handlers[health(alpha.id, service.id)] = async () => json({}, status);
    await act(async () => { await client.invalidateQueries({ queryKey: healthKeys.latest(alpha.id, service.id) }); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Cached observations are hidden");
    expect(screen.queryByRole("region", { name: "Latest health check" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Recent health checks" })).not.toBeInTheDocument();
    expect(healthCalls(alpha.id, service.id)).toHaveLength(2);
    expect(endSession).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
});

test("registry-to-details navigation reuses a fresh latest cache and loads only the new history query", async () => {
    mount("/services");
    await screen.findByRole("link", { name: "Alpha API" });
    await within(screen.getByRole("link", { name: "Alpha API" }).closest("tr")!).findByText("UP");
    const before = healthCalls(alpha.id, service.id).length;
    await userEvent.click(screen.getByRole("link", { name: "Alpha API" }));
    await screen.findByRole("heading", { name: "Alpha API" });
    await within(historyCard()).findByRole("list");
    expect(healthCalls(alpha.id, service.id)).toHaveLength(before);
    expect(healthCalls(alpha.id, service.id, "history")).toHaveLength(1);
});

test("tenant switching suppresses old counts even with global keepPreviousData and ignores a late response", async () => {
    const pending = deferred();
    handlers[health(alpha.id, service.id)] = () => pending.promise;
    const client = mount("/", new QueryClient({ defaultOptions: { queries: { retry: false, placeholderData: keepPreviousData } } }));
    await waitFor(() => expect(healthCalls(alpha.id, service.id).length).toBeGreaterThan(0));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), beta.id);
    expect(await screen.findByRole("group", { name: "Latest DOWN" })).toHaveTextContent("DOWN1");
    expect(screen.getByRole("group", { name: "Latest UP" })).toHaveTextContent("UP0");
    await act(async () => pending.resolve(json(recordedHealth(alpha.id, service.id))));
    expect(screen.getByRole("group", { name: "Latest DOWN" })).toHaveTextContent("DOWN1");
    expect(screen.getByRole("group", { name: "Latest UP" })).toHaveTextContent("UP0");
    expect(client.getQueryData(healthKeys.latest(beta.id, betaService.id))).toMatchObject({ organisationId: beta.id, status: "DOWN" });
});

test("details switching tenants cannot display a late previous-service result or history", async () => {
    const pending = deferred();
    handlers[health(alpha.id, service.id)] = () => pending.promise;
    mount("/services/alpha-up");
    await waitFor(() => expect(healthCalls(alpha.id, service.id).length).toBeGreaterThan(0));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), beta.id);
    await screen.findByRole("alert");
    await act(async () => pending.resolve(json(recordedHealth(alpha.id, service.id))));
    expect(screen.queryByRole("region", { name: "Latest health check" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Recent health checks" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alpha API" })).not.toBeInTheDocument();
});

test("malformed cross-tenant monitoring responses fail closed rather than updating Dashboard counts", async () => {
    handlers[health(alpha.id, service.id)] = async () => json(recordedHealth(beta.id, service.id));
    mount();
    expect(await screen.findByRole("alert")).toHaveTextContent("could not be verified");
    expect(screen.queryByRole("group", { name: "Latest UP" })).not.toBeInTheDocument();
});

test("zero services show an honest empty monitoring state and make no health requests", async () => {
    handlers[collection(alpha.id)] = async () => json([]);
    mount();
    await screen.findByText("No enabled HTTP services to check.");
    expect(screen.getByText("No services registered yet")).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Latest UP" })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => new URL(url).pathname.includes("/health/"))).toHaveLength(0);
});

test("a revoked monitoring permission hides previously cached Dashboard totals", async () => {
    const client = mount();
    await screen.findByRole("group", { name: "Latest UP" });
    handlers[health(alpha.id, service.id)] = async () => json({}, 403);
    await act(async () => { await client.invalidateQueries({ queryKey: healthKeys.latest(alpha.id, service.id) }); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Health totals are hidden");
    expect(screen.queryByRole("group", { name: "Latest UP" })).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Latest DOWN" })).not.toBeInTheDocument();
});

test("registry hides a cached UP observation after a permission error", async () => {
    const client = mount("/services");
    await screen.findByRole("link", { name: "Alpha API" });
    const row = screen.getByRole("link", { name: "Alpha API" }).closest("tr")!;
    await within(row).findByText("UP");
    handlers[health(alpha.id, service.id)] = async () => json({}, 403);
    await act(async () => { await client.invalidateQueries({ queryKey: healthKeys.latest(alpha.id, service.id) }); });
    expect(await within(row).findByText("Health unavailable")).toBeInTheDocument();
    expect(within(row).queryByText("UP", { exact: true })).not.toBeInTheDocument();
});

test("a loaded tenant's health totals disappear while the next tenant's observations are pending", async () => {
    const pending = deferred();
    handlers[health(beta.id, betaService.id)] = () => pending.promise;
    mount("/", new QueryClient({ defaultOptions: { queries: { retry: false, placeholderData: keepPreviousData } } }));
    await screen.findByRole("group", { name: "Latest UP" });
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), beta.id);
    await screen.findByText("Loading monitoring observations…");
    expect(screen.queryByRole("group", { name: "Latest UP" })).not.toBeInTheDocument();
    await act(async () => pending.resolve(json(recordedHealth(beta.id, betaService.id, "DOWN"))));
    expect(await screen.findByRole("group", { name: "Latest DOWN" })).toHaveTextContent("DOWN1");
    expect(screen.getByRole("group", { name: "Latest UP" })).toHaveTextContent("UP0");
});

test("monitoring polls at 60 seconds, not on rerenders, and pauses while unfocused", async () => {
    vi.useFakeTimers();
    focusManager.setFocused(true);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
    clients.push(client);
    client.setQueryData(healthKeys.latest(alpha.id, service.id), recordedHealth(alpha.id, service.id));
    const { result, rerender } = renderHook(() => useLatestHealth(alpha.id, service.id, true, true), {
        wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    });
    expect(result.current.data?.status).toBe("UP");
    rerender();
    await act(async () => { await vi.advanceTimersByTimeAsync(59_999); });
    expect(healthCalls(alpha.id, service.id)).toHaveLength(0);
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(healthCalls(alpha.id, service.id)).toHaveLength(1);
    focusManager.setFocused(false);
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(healthCalls(alpha.id, service.id)).toHaveLength(1);
});

test("paused services keep historical data without interval polling", async () => {
    vi.useFakeTimers();
    const client = new QueryClient();
    clients.push(client);
    client.setQueryData(healthKeys.latest(alpha.id, service.id), recordedHealth(alpha.id, service.id, "UP", false));
    renderHook(() => useLatestHealth(alpha.id, service.id, true, false), {
        wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    });
    await act(async () => { await vi.advanceTimersByTimeAsync(120_000); });
    expect(healthCalls(alpha.id, service.id)).toHaveLength(0);
});
