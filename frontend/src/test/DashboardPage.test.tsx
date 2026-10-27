import { StrictMode } from "react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider, keepPreviousData } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import DashboardPage from "../pages/DashboardPage";
import ServicesPage from "../pages/ServicesPage";
import { OrganisationProvider } from "../context/OrganisationProvider";
import { useOrganisation } from "../hooks/useOrganisation";
import { endSession, getAccessToken } from "../auth/auth";
import type { Organisation } from "../types/Organisation";
import type { MonitoredService } from "../types/MonitoredService";
import { unknownHealth } from "./healthFixtures";
import { emptyAlertResponse } from "./alertFixtures";

// Exercise the real provider, API clients and query cache with controlled HTTP.
vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn() }));
const fetchMock = vi.fn<(url: string, options: RequestInit) => Promise<Response>>();
const alpha: Organisation = { id: "alpha", name: "Alpha Operations", slug: "alpha", status: "ACTIVE", createdAt: "2026-10-25" };
const beta: Organisation = { ...alpha, id: "beta", name: "Beta Operations", slug: "beta" };
const service: MonitoredService = {
    id: "payments", organisationId: alpha.id, name: "Payments API", owner: "Payments team",
    serviceType: "HTTP", environment: "PRODUCTION", enabled: true,
    baseUrl: "https://payments.example.com", healthEndpoint: "/health",
};
const alphaServices: MonitoredService[] = [service,
    { ...service, id: "worker", name: "Job worker", enabled: false, environment: "STAGING" },
    { ...service, id: "metrics", name: "Metrics", environment: "PRODUCTION" },
];
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
let organisationsResponse: () => Promise<Response>;
let serviceResponses: Record<string, () => Promise<Response>>;
const clients: QueryClient[] = [];

beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("fetch", fetchMock);
    vi.mocked(getAccessToken).mockResolvedValue("test-dashboard-token");
    organisationsResponse = async () => json([alpha, beta]);
    serviceResponses = { alpha: async () => json(alphaServices), beta: async () => json([]) };
    fetchMock.mockImplementation(async (url) => {
        const path = new URL(url).pathname;
        const alerts = emptyAlertResponse(url);
        if (alerts !== undefined) return json(alerts);
        if (path === "/api/organisations") return (await organisationsResponse()).clone();
        const health = path.match(/^\/api\/organisations\/([^/]+)\/monitored-services\/([^/]+)\/health\/latest$/);
        if (health) return json(unknownHealth(health[1], health[2]));
        const match = path.match(/^\/api\/organisations\/([^/]+)\/monitored-services$/);
        if (match && serviceResponses[match[1]]) return (await serviceResponses[match[1]]()).clone();
        throw new Error(`Unexpected dashboard API request: ${path}`);
    });
});
afterEach(() => {
    clients.splice(0).forEach((client) => client.clear());
    vi.unstubAllGlobals();
});

function OrganisationSwitcher() {
    const { organisations, currentOrganisation, organisationsPending, setCurrentOrganisationId } = useOrganisation();
    return <select aria-label="Test organisation" value={currentOrganisation?.id ?? ""} disabled={organisationsPending || !organisations.length}
        onChange={(event) => setCurrentOrganisationId(event.target.value)}>
        {!organisations.length && <option value="">No organisation</option>}
        {organisations.map((organisation) => <option key={organisation.id} value={organisation.id}>{organisation.name}</option>)}
    </select>;
}

function renderDashboard(client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })) {
    clients.push(client);
    const view = render(<StrictMode><QueryClientProvider client={client}><OrganisationProvider>
        <MemoryRouter initialEntries={["/"]}><OrganisationSwitcher /><Routes>
            <Route path="/" element={<DashboardPage />} />
            <Route path="/services" element={<ServicesPage />} />
            <Route path="/services/new" element={<h1>Service registration route</h1>} />
            <Route path="/organisations/new" element={<h1>Organisation creation route</h1>} />
        </Routes></MemoryRouter>
    </OrganisationProvider></QueryClientProvider></StrictMode>);
    return { client, ...view };
}

function expectMetric(label: string, value: number) {
    const metric = screen.getByRole("group", { name: label });
    expect(within(metric).getByText(value.toLocaleString(), { exact: true })).toBeInTheDocument();
}
function expectTotals(total: number, enabled: number, disabled: number) {
    expectMetric("Total registered services", total);
    expectMetric("Enabled services", enabled);
    expectMetric("Disabled services", disabled);
}
async function readyDashboard() {
    // Initial rendering waits on both the organisation and service requests.
    return screen.findByRole("heading", { name: "Dashboard" }, { timeout: 5000 });
}
function requests(organisationId: string) {
    return fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === `/api/organisations/${organisationId}/monitored-services`);
}
function deferredResponse() {
    let resolve!: (response: Response) => void;
    let reject!: (error: Error) => void;
    const promise = new Promise<Response>((complete, fail) => { resolve = complete; reject = fail; });
    return { promise, resolve, reject };
}

test.each([
    [[], 0, 0], [[true], 1, 0], [[false], 0, 1], [[true, false, true], 2, 1], [[false, false, false], 0, 3],
] as const)("derives real totals for enabled flags %j", async (states, enabled, disabled) => {
    serviceResponses.alpha = async () => json(states.map((state, index) => ({ ...service, id: `service-${index}`, enabled: state })));
    renderDashboard();
    await readyDashboard();
    expectTotals(states.length, enabled, disabled);
    expect(requests(alpha.id)).toHaveLength(1);
});

test("aggregates all configured environments including zero counts", async () => {
    serviceResponses.alpha = async () => json([...alphaServices, { ...service, id: "dev", environment: "DEVELOPMENT" }]);
    renderDashboard();
    const environments = await screen.findByRole("region", { name: "Services by environment" });
    for (const [label, count] of [["Production", 2], ["Staging", 1], ["Development", 1], ["Test", 0]] as const) {
        expect(within(environments).getByRole("group", { name: label })).toHaveTextContent(`${count} services`);
    }
    expectTotals(4, 3, 1);
});

test("an empty organisation has real zero totals, an onboarding action and no fabricated monitoring data", async () => {
    serviceResponses.alpha = async () => json([]);
    renderDashboard();
    await screen.findByRole("heading", { name: "No services registered yet" });
    expectTotals(0, 0, 0);
    expect(screen.queryByRole("region", { name: "Services by environment" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("link", { name: "Register your first service" }));
    expect(screen.getByRole("heading", { name: "Service registration route" })).toBeInTheDocument();
});

test("HTTP monitoring and real alert counts are available while uptime and incidents remain unavailable", async () => {
    renderDashboard();
    const monitoring = await screen.findByRole("region", { name: "Monitoring capabilities" });
    expect(within(monitoring).getAllByText("Not implemented")).toHaveLength(2);
    for (const feature of ["Uptime calculations", "Incidents"]) {
        expect(within(monitoring).getByText(feature).closest("li")).toHaveTextContent("Not implemented");
    }
    expect(screen.getByText(/Enabled does not mean healthy/)).toBeInTheDocument();
    expect(await screen.findByRole("group", { name: "Active alerts" })).toHaveTextContent("0");
    expect(screen.queryByText(/\d+\s*%/)).not.toBeInTheDocument();
    expect(within(monitoring).queryByText(/^\d+$/)).not.toBeInTheDocument();
});

test("uses the authenticated API endpoint and retains raw service arrays in the shared query cache", async () => {
    const { client } = renderDashboard();
    await readyDashboard();
    const [url, options] = requests(alpha.id)[0];
    expect(new URL(url).pathname).toBe("/api/organisations/alpha/monitored-services");
    expect(new Headers(options.headers).get("Authorization")).toBe("Bearer test-dashboard-token");
    expect(options.redirect).toBe("error");
    expect(client.getQueryData(["monitored-services", alpha.id])).toEqual(alphaServices);
    expect(requests(beta.id)).toHaveLength(0);
});

test("the registry reuses the same fresh query cache and service detail links", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000, refetchOnWindowFocus: false } } });
    renderDashboard(client);
    await readyDashboard();
    await userEvent.click(screen.getByRole("link", { name: "View services" }));
    const table = await screen.findByRole("table");
    expect(within(table).getByRole("link", { name: "Payments API" })).toHaveAttribute("href", "/services/payments");
    expect(requests(alpha.id)).toHaveLength(1);
    expect(client.getQueryData(["monitored-services", alpha.id])).toEqual(alphaServices);
});

test("the primary Add service action preserves the registration route", async () => {
    renderDashboard();
    await readyDashboard();
    await userEvent.click(screen.getByRole("link", { name: "Add service" }));
    expect(screen.getByRole("heading", { name: "Service registration route" })).toBeInTheDocument();
});

test("initial loading never presents placeholder zero metrics", async () => {
    const pending = deferredResponse();
    serviceResponses.alpha = () => pending.promise;
    renderDashboard();
    expect(await screen.findByRole("heading", { name: "Loading dashboard..." })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(alpha.name);
    expect(screen.queryByRole("group", { name: "Total registered services" })).not.toBeInTheDocument();
    await act(async () => pending.resolve(json(alphaServices)));
    await readyDashboard();
    expectTotals(3, 2, 1);
});

test("network failure hides metrics, reports an error and supports explicit retry", async () => {
    serviceResponses.alpha = async () => { throw new Error("Network unavailable"); };
    renderDashboard();
    expect(await screen.findByRole("alert")).toHaveTextContent("Network unavailable");
    expect(screen.queryByRole("group", { name: "Total registered services" })).not.toBeInTheDocument();
    serviceResponses.alpha = async () => json(alphaServices);
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    await readyDashboard();
    expectTotals(3, 2, 1);
    expect(requests(alpha.id)).toHaveLength(2);
});

test.each([401, 403])("HTTP %i never exposes cached metrics or causes a retry loop", async (status) => {
    serviceResponses.alpha = async () => json({}, status);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
    client.setQueryData(["monitored-services", alpha.id], alphaServices);
    renderDashboard(client);
    expect(await screen.findByRole("alert")).toHaveTextContent(status === 401 ? "Authentication required" : "You do not have permission");
    expect(screen.queryByRole("group", { name: "Total registered services" })).not.toBeInTheDocument();
    expect(requests(alpha.id)).toHaveLength(1);
    if (status === 401) expect(endSession).toHaveBeenCalledTimes(1);
    else expect(endSession).not.toHaveBeenCalled();
});

test("switching organisations shows loading rather than previous totals, even with global keepPreviousData", async () => {
    const pending = deferredResponse();
    serviceResponses.beta = () => pending.promise;
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, placeholderData: keepPreviousData, refetchOnWindowFocus: false } } });
    renderDashboard(client);
    await readyDashboard();
    expectTotals(3, 2, 1);
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Test organisation" }), beta.id);
    expect(await screen.findByRole("heading", { name: "Loading dashboard..." })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(beta.name);
    expect(screen.queryByRole("group", { name: "Total registered services" })).not.toBeInTheDocument();
    await act(async () => pending.resolve(json([{ ...service, id: "beta-service", organisationId: beta.id, enabled: false }])));
    await readyDashboard();
    expectTotals(1, 0, 1);
});

test("a late previous-tenant response can update only its own cache, never the current metrics", async () => {
    const pending = deferredResponse();
    serviceResponses.alpha = () => pending.promise;
    serviceResponses.beta = async () => json([{ ...service, id: "beta-service", organisationId: beta.id, enabled: false }]);
    const { client } = renderDashboard();
    await waitFor(() => expect(requests(alpha.id)).toHaveLength(1));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Test organisation" }), beta.id);
    await readyDashboard();
    expectTotals(1, 0, 1);
    await act(async () => pending.resolve(json(alphaServices)));
    await waitFor(() => expect(client.getQueryData(["monitored-services", alpha.id])).toEqual(alphaServices));
    expectTotals(1, 0, 1);
    expect(screen.getByRole("banner")).toHaveTextContent(beta.name);
    expect(client.getQueryData(["monitored-services", beta.id])).toHaveLength(1);
});

test("switching back to a cached tenant uses only that tenant's totals", async () => {
    renderDashboard();
    await readyDashboard();
    const user = userEvent.setup();
    await user.selectOptions(screen.getByRole("combobox", { name: "Test organisation" }), beta.id);
    await screen.findByRole("heading", { name: "No services registered yet" });
    expectTotals(0, 0, 0);
    await user.selectOptions(screen.getByRole("combobox", { name: "Test organisation" }), alpha.id);
    await screen.findByRole("heading", { name: "Services by environment" });
    expectTotals(3, 2, 1);
});

test("the existing list invalidation refreshes totals after service configuration changes", async () => {
    const { client } = renderDashboard();
    await readyDashboard();
    expectTotals(3, 2, 1);
    const pending = deferredResponse();
    serviceResponses.alpha = () => pending.promise;
    let refresh!: Promise<void>;
    act(() => { refresh = client.invalidateQueries({ queryKey: ["monitored-services", alpha.id] }); });
    expect(await screen.findByRole("status")).toHaveTextContent("Refreshing service totals");
    expectTotals(3, 2, 1);
    await act(async () => { pending.resolve(json([{ ...service, enabled: false }])); await refresh; });
    await waitFor(() => expectMetric("Total registered services", 1));
    expectTotals(1, 0, 1);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
});

test("a failed background refresh hides stale metrics until a successful retry", async () => {
    const { client } = renderDashboard();
    await readyDashboard();
    serviceResponses.alpha = async () => { throw new Error("Refresh failed"); };
    await act(async () => { await client.invalidateQueries({ queryKey: ["monitored-services", alpha.id] }); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Refresh failed");
    expect(screen.queryByRole("group", { name: "Enabled services" })).not.toBeInTheDocument();
    serviceResponses.alpha = async () => json([]);
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByRole("heading", { name: "No services registered yet" });
    expectTotals(0, 0, 0);
});

test("a response containing another tenant's records fails closed without counting them", async () => {
    serviceResponses.alpha = async () => json([...alphaServices, { ...service, organisationId: beta.id }]);
    renderDashboard();
    expect(await screen.findByRole("alert")).toHaveTextContent("does not match the selected organisation");
    expect(screen.queryByRole("group", { name: "Total registered services" })).not.toBeInTheDocument();
    serviceResponses.alpha = async () => json(alphaServices);
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    await readyDashboard();
    expectTotals(3, 2, 1);
});

test("long organisation names and larger real counts remain available as text", async () => {
    const longName = "LongOrganisationName".repeat(12);
    organisationsResponse = async () => json([{ ...alpha, name: longName }]);
    serviceResponses.alpha = async () => json(Array.from({ length: 1234 }, (_, index) => ({ ...service, id: `service-${index}`, enabled: index % 2 === 0 })));
    renderDashboard();
    await readyDashboard();
    expect(screen.getByRole("banner")).toHaveTextContent(longName);
    expectTotals(1234, 617, 617);
});

test("organisation loading does not issue an unscoped service request", async () => {
    const pending = deferredResponse();
    organisationsResponse = () => pending.promise;
    renderDashboard();
    expect(screen.getByRole("status")).toHaveTextContent("Loading organisations...");
    expect(requests(alpha.id)).toHaveLength(0);
    expect(requests(beta.id)).toHaveLength(0);
    await act(async () => pending.resolve(json([alpha, beta])));
    await readyDashboard();
});

test("organisation errors prevent service requests", async () => {
    organisationsResponse = async () => json({}, 403);
    renderDashboard();
    expect(await screen.findByRole("alert")).toHaveTextContent("Unable to load organisations");
    expect(requests(alpha.id)).toHaveLength(0);
    expect(requests(beta.id)).toHaveLength(0);
});

test("a user without organisations gets the existing organisation-creation route", async () => {
    organisationsResponse = async () => json([]);
    renderDashboard();
    await screen.findByRole("heading", { name: "Select an organisation" });
    expect(requests(alpha.id)).toHaveLength(0);
    expect(screen.queryByRole("group", { name: "Total registered services" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("link", { name: "Create Organisation" }));
    expect(screen.getByRole("heading", { name: "Organisation creation route" })).toBeInTheDocument();
});
