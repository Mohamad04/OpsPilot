import { StrictMode } from "react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { OrganisationProvider } from "../context/OrganisationProvider";
import { useOrganisation } from "../hooks/useOrganisation";
import ServicesPage from "../pages/ServicesPage";
import ServiceDetailsPage from "../pages/ServiceDetailsPage";
import DashboardPage from "../pages/DashboardPage";
import { endSession, getAccessToken } from "../auth/auth";
import type { MonitoredService } from "../types/MonitoredService";
import { unknownHealth } from "./healthFixtures";
import { emptyAlertResponse } from "./alertFixtures";

// Real provider, router, query cache and authenticated API clients; only HTTP/auth are controlled.
vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn() }));
const alpha = { id: "alpha", name: "Alpha team", slug: "alpha", status: "ACTIVE", createdAt: "2026-10-26" };
const beta = { ...alpha, id: "beta", name: "Beta team", slug: "beta" };
const alphaService: MonitoredService = {
    id: "alpha-service", organisationId: alpha.id, name: "Alpha confidential API", owner: "Alpha team",
    serviceType: "HTTP", environment: "PRODUCTION", enabled: true,
    baseUrl: "https://alpha.example.test", healthEndpoint: "/health",
};
const betaService: MonitoredService = { ...alphaService, id: "beta-service", organisationId: beta.id, name: "Beta API", baseUrl: "https://beta.example.test", enabled: false };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const collection = (id: string) => `/api/organisations/${id}/monitored-services`;
const detail = (id: string) => `${collection(id)}/${alphaService.id}`;
const clients: QueryClient[] = [];
let handlers: Record<string, () => Promise<Response>>;
const fetchMock = vi.fn<(url: string, options: RequestInit) => Promise<Response>>();

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getAccessToken).mockResolvedValue("disposable-test-token");
    handlers = {
        "/api/organisations": async () => json([alpha, beta]),
        [collection(alpha.id)]: async () => json([alphaService]),
        [collection(beta.id)]: async () => json([betaService]),
        [detail(alpha.id)]: async () => json(alphaService),
        // The route retains Alpha's service ID after switching; Beta must not resolve it.
        [detail(beta.id)]: async () => json({ status: 404 }, 404),
    };
    fetchMock.mockImplementation(async (url, options) => {
        expect(new Headers(options.headers).get("Authorization")).toBe("Bearer disposable-test-token");
        const path = new URL(url).pathname;
        const alerts = emptyAlertResponse(url);
        if (alerts !== undefined) return json(alerts);
        const health = path.match(/^\/api\/organisations\/([^/]+)\/monitored-services\/([^/]+)\/health\/(latest|history)$/);
        if (health) return json(health[3] === "latest" ? unknownHealth(health[1], health[2]) : []);
        if (!handlers[path]) throw new Error(`Unexpected tenant API path: ${path}`);
        return (await handlers[path]()).clone();
    });
    vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => { clients.splice(0).forEach((client) => client.clear()); vi.unstubAllGlobals(); });

function Switcher() {
    const { currentOrganisation, organisations, setCurrentOrganisationId } = useOrganisation();
    return <select aria-label="Test organisation" value={currentOrganisation?.id ?? ""}
        onChange={(event) => setCurrentOrganisationId(event.target.value)}>
        {!organisations.length && <option value="">Loading</option>}
        {organisations.map((organisation) => <option key={organisation.id} value={organisation.id}>{organisation.name}</option>)}
    </select>;
}

type Page = "registry" | "details";
function mount(page: Page) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
    clients.push(client);
    render(<StrictMode><QueryClientProvider client={client}><OrganisationProvider>
        <MemoryRouter initialEntries={[page === "registry" ? "/services" : `/services/${alphaService.id}`]}>
            <Switcher /><Routes>
                <Route path="/services" element={<ServicesPage />} />
                <Route path="/services/:id" element={<ServiceDetailsPage />} />
                <Route path="/" element={<DashboardPage />} />
            </Routes>
        </MemoryRouter>
    </OrganisationProvider></QueryClientProvider></StrictMode>);
    return client;
}

const key = (page: Page, tenant: string) => page === "registry" ? ["monitored-services", tenant] : ["monitored-service", tenant, alphaService.id];
const path = (page: Page, tenant: string) => page === "registry" ? collection(tenant) : detail(tenant);
function deferred() {
    let resolve!: (response: Response) => void;
    const promise = new Promise<Response>((complete) => { resolve = complete; });
    return { promise, resolve };
}
async function alphaVisible(page: Page) {
    return screen.findByRole(page === "registry" ? "link" : "heading", { name: alphaService.name });
}
function expectNoAlpha() {
    expect(screen.queryByRole("link", { name: alphaService.name })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: alphaService.name })).not.toBeInTheDocument();
    expect(screen.queryByText(alphaService.baseUrl)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Disable service" })).not.toBeInTheDocument();
}

test.each(["registry", "details"] as const)("%s hides previous tenant data while the next tenant is pending", async (page) => {
    const pending = deferred();
    handlers[path(page, beta.id)] = () => pending.promise;
    mount(page);
    await alphaVisible(page);
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Test organisation" }), beta.id);
    expect(await screen.findByRole("status")).toHaveTextContent(page === "registry" ? "Loading services" : "Loading service");
    expectNoAlpha();
    await act(async () => pending.resolve(page === "registry" ? json([betaService]) : json({}, 404)));
    if (page === "registry") expect(await screen.findByRole("link", { name: betaService.name })).toBeInTheDocument();
    else expect(await screen.findByRole("alert")).toHaveTextContent("Unable to load service");
    expectNoAlpha();
});

test.each(["registry", "details"] as const)("%s late Alpha response cannot replace Beta data or error state", async (page) => {
    const pending = deferred();
    handlers[path(page, alpha.id)] = () => pending.promise;
    const client = mount(page);
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => new URL(url).pathname === path(page, alpha.id))).toBe(true));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Test organisation" }), beta.id);
    if (page === "registry") await screen.findByRole("link", { name: betaService.name });
    else await screen.findByRole("alert");
    await act(async () => pending.resolve(json(page === "registry" ? [alphaService] : alphaService)));
    await waitFor(() => expect(client.getQueryData(key(page, alpha.id))).toEqual(page === "registry" ? [alphaService] : alphaService));
    expectNoAlpha();
    expect(screen.getByRole("combobox", { name: "Test organisation" })).toHaveValue(beta.id);
    if (page === "registry") expect(screen.getByRole("link", { name: betaService.name })).toBeInTheDocument();
    else expect(screen.getByRole("alert")).toHaveTextContent("Unable to load service");
});

test.each([403, 404])("registry discards visible cached data after the backend denies access (%i)", async (status) => {
    const client = mount("registry");
    await alphaVisible("registry");
    handlers[collection(alpha.id)] = async () => json({}, status);
    await act(async () => { await client.invalidateQueries({ queryKey: key("registry", alpha.id) }); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Unable to load services");
    expectNoAlpha();
    expect(endSession).not.toHaveBeenCalled();
    handlers[collection(alpha.id)] = async () => json([alphaService]);
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    await alphaVisible("registry");
});

test.each([403, 404])("details discards visible cached data after the backend denies access (%i)", async (status) => {
    const client = mount("details");
    await alphaVisible("details");
    handlers[detail(alpha.id)] = async () => json({}, status);
    await act(async () => { await client.invalidateQueries({ queryKey: key("details", alpha.id) }); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Unable to load service");
    expectNoAlpha();
    expect(endSession).not.toHaveBeenCalled();
    handlers[detail(alpha.id)] = async () => json(alphaService);
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    await alphaVisible("details");
});

test.each(["registry", "details"] as const)("%s ignores a late access-denied response for the previous tenant", async (page) => {
    const pending = deferred();
    handlers[path(page, alpha.id)] = () => pending.promise;
    mount(page);
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => new URL(url).pathname === path(page, alpha.id))).toBe(true));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Test organisation" }), beta.id);
    if (page === "registry") await screen.findByRole("link", { name: betaService.name });
    else await screen.findByRole("alert");
    await act(async () => pending.resolve(json({}, 403)));
    expectNoAlpha();
    expect(screen.getByRole("combobox", { name: "Test organisation" })).toHaveValue(beta.id);
    if (page === "registry") {
        expect(screen.getByRole("link", { name: betaService.name })).toBeInTheDocument();
        expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    } else {
        expect(screen.getByRole("alert")).toHaveTextContent("Could not get monitored service");
        expect(screen.getByRole("alert")).not.toHaveTextContent("You do not have permission");
    }
    expect(endSession).not.toHaveBeenCalled();
});

test("switching cached tenants always uses the active tenant's collection", async () => {
    const client = mount("registry");
    await alphaVisible("registry");
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Test organisation" }), beta.id);
    await screen.findByRole("link", { name: betaService.name });
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Test organisation" }), alpha.id);
    await alphaVisible("registry");
    expect(screen.queryByRole("link", { name: betaService.name })).not.toBeInTheDocument();
    expect(client.getQueryData(key("registry", beta.id))).toEqual([betaService]);
});

test("membership-list refresh removing the selected tenant falls back to an authorised tenant without old data", async () => {
    const client = mount("registry");
    await alphaVisible("registry");
    handlers["/api/organisations"] = async () => json([beta]);
    await act(async () => { await client.invalidateQueries({ queryKey: ["organisations"] }); });
    await screen.findByRole("link", { name: betaService.name });
    expectNoAlpha();
    expect(within(screen.getByRole("combobox", { name: "Test organisation" })).queryByRole("option", { name: alpha.name })).not.toBeInTheDocument();
});
