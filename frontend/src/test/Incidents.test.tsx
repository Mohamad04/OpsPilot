import { StrictMode } from "react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { keepPreviousData, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import App from "../App";
import { OrganisationProvider } from "../context/OrganisationProvider";
import { getAccessToken } from "../auth/auth";
import { incidentKeys } from "../hooks/useIncidents";
import type { Incident } from "../types/Incident";
import type { Organisation } from "../types/Organisation";
import { EVENT, INCIDENT, ORG, OTHER_ORG, SECOND_INCIDENT, SERVICE, USER, createdAt, eventFixture, incidentFixture, incidentPage, inStatus } from "./incidentFixtures";

vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn(), logout: vi.fn() }));
vi.mock("../auth/useAuth", () => ({ useAuth: () => ({ status: "authenticated", user: { displayName: "Incident tester" } }) }));
const alpha: Organisation = { id: ORG, name: "Alpha operations", slug: "alpha", status: "ACTIVE", createdAt };
const beta = { ...alpha, id: OTHER_ORG, name: "Beta operations", slug: "beta" };
const service = { id: SERVICE, organisationId: ORG, name: "Payments API", serviceType: "HTTP", environment: "TEST",
    owner: "Operations", enabled: true, baseUrl: "https://fixture.example.test", healthEndpoint: "/health" };
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
const fetchMock = vi.fn<(url: string, options: RequestInit) => Promise<Response>>();
type Handler = (url: URL, options: RequestInit) => Promise<Response>;
let handlers: Record<string, Handler>;
let records: Record<string, Incident[]>;
let organisations: Organisation[];
const base = `/api/organisations/${ORG}/incidents`;
const otherBase = `/api/organisations/${OTHER_ORG}/incidents`;
const clients: QueryClient[] = [];
beforeEach(() => {
    vi.clearAllMocks(); vi.stubGlobal("fetch", fetchMock); vi.mocked(getAccessToken).mockResolvedValue("incident-page-token");
    handlers = {}; organisations = [alpha, beta];
    records = { [ORG]: [incidentFixture(), { ...inStatus("RESOLVED"), id: SECOND_INCIDENT, title: "Recovered outage", severity: "LOW" }], [OTHER_ORG]: [] };
    fetchMock.mockImplementation(async (url, options) => {
        expect(new Headers(options.headers).get("Authorization")).toBe("Bearer incident-page-token");
        const parsed = new URL(url); const path = parsed.pathname;
        if (handlers[path]) return handlers[path](parsed, options);
        if (path === "/api/organisations") return json(organisations);
        const match = path.match(/^\/api\/organisations\/([^/]+)(.*)$/);
        if (!match) throw new Error(`Unexpected fixture URL ${url}`);
        const [, org, suffix] = match;
        if (suffix === "/monitored-services") return json(org === ORG ? [service] : []);
        const page = Number(parsed.searchParams.get("page") ?? 0);
        if (suffix === "/incidents") {
            const items = records[org].filter(item => (!parsed.searchParams.get("status") || item.status === parsed.searchParams.get("status"))
                && (!parsed.searchParams.get("severity") || item.severity === parsed.searchParams.get("severity"))
                && (!parsed.searchParams.get("serviceId") || item.monitoredServiceId === parsed.searchParams.get("serviceId")))
                .sort((a, b) => parsed.searchParams.get("sort") === "createdAt,asc"
                    ? a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id) : b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id));
            return json(incidentPage(items.slice(page * 20, (page + 1) * 20), page, 20, items.length));
        }
        if (suffix.endsWith("/events")) return json(incidentPage([eventFixture({ organisationId: org, incidentId: suffix.split("/")[2] })]));
        const found = records[org].find(item => item.id === suffix.split("/")[2]);
        if (found) return json(found);
        return json({}, 404);
    });
});
afterEach(() => { clients.splice(0).forEach(client => client.clear()); vi.unstubAllGlobals(); });
function mount(path = "/incidents", client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false, placeholderData: keepPreviousData } } })) {
    clients.push(client);
    render(<StrictMode><QueryClientProvider client={client}><OrganisationProvider><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></OrganisationProvider></QueryClientProvider></StrictMode>);
    return client;
}
const registry = () => screen.getByRole("region", { name: "Incident registry" });
const calls = (path = base) => fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === path);
function deferred() { let resolve!: (value: Response) => void; const promise = new Promise<Response>(done => { resolve = done; }); return { resolve, promise }; }

test("registry shows actual ongoing/historical incidents and routes to full details and timeline", async () => {
    mount();
    const link = await screen.findByRole("link", { name: "Payments availability incident" });
    expect(within(registry()).getByText("Open")).toBeInTheDocument(); expect(within(registry()).getAllByText("Resolved").length).toBeGreaterThan(0);
    expect(within(registry()).getAllByText("Payments API")).toHaveLength(2);
    expect(within(registry()).getByText(/Ongoing incident/)).toBeInTheDocument(); expect(within(registry()).getByText(/Historical incident/)).toBeInTheDocument();
    await userEvent.click(link);
    expect(await screen.findByRole("heading", { name: "Payments availability incident" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Incident created" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Payments API" })).toHaveAttribute("href", `/services/${SERVICE}`);
    expect(screen.getByText("Not acknowledged")).toBeInTheDocument(); expect(screen.getByText("No resolution recorded")).toBeInTheDocument();
    expect(screen.getByText(INCIDENT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Acknowledged" })).not.toBeInTheDocument();
    expect(screen.getByText(/permissions are unavailable/)).toBeInTheDocument();
});
test("status, severity, service and sorting are applied on the server and Clear resets them", async () => {
    mount(); await screen.findByRole("link", { name: "Payments availability incident" });
    await userEvent.selectOptions(screen.getByLabelText("Status"), "RESOLVED"); await userEvent.selectOptions(screen.getByLabelText("Severity"), "LOW");
    await userEvent.selectOptions(screen.getByLabelText("Affected service"), SERVICE); await userEvent.selectOptions(screen.getByLabelText("Created"), "createdAt,asc");
    await userEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => expect(within(registry()).queryByText("Payments availability incident")).not.toBeInTheDocument());
    expect(within(registry()).getByText("Recovered outage")).toBeInTheDocument();
    const params = new URL(calls().at(-1)![0]).searchParams;
    expect(Object.fromEntries(params)).toEqual({ status: "RESOLVED", severity: "LOW", serviceId: SERVICE, page: "0", size: "20", sort: "createdAt,asc" });
    await userEvent.click(screen.getByRole("button", { name: "Clear filters" })); await screen.findByRole("link", { name: "Payments availability incident" });
    expect(screen.getByLabelText("Status")).toHaveValue(""); expect(new URL(calls().at(-1)![0]).searchParams.has("serviceId")).toBe(false);
});
test("pagination is bounded, server-driven and resets after filters change", async () => {
    records[ORG] = Array.from({ length: 21 }, (_, index) => incidentFixture({ id: `${(index + 1).toString(16).padStart(8, "0")}-aaaa-4aaa-8aaa-aaaaaaaaaaaa`, title: `Outage ${index + 1}` }));
    mount(); await screen.findByRole("link", { name: "Outage 21" });
    const pages = () => screen.getByRole("navigation", { name: "Incident pages" });
    expect(within(pages()).getByRole("button", { name: "Previous" })).toBeDisabled();
    await userEvent.click(within(pages()).getByRole("button", { name: "Next" }));
    await waitFor(() => expect(within(registry()).getAllByRole("link", { name: /^Outage / })).toHaveLength(1));
    expect(new URL(calls().at(-1)![0]).searchParams.get("page")).toBe("1"); expect(within(pages()).getByRole("button", { name: "Next" })).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText("Status"), "OPEN"); await userEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => expect(new URL(calls().at(-1)![0]).searchParams.get("page")).toBe("0"));
});
test("empty, filtered-empty, loading, error and manual retry states are honest", async () => {
    const pending = deferred(); handlers[base] = () => pending.promise; mount();
    expect(await screen.findByText("Loading incidents…")).toBeInTheDocument(); expect(screen.queryByText("No incidents recorded")).not.toBeInTheDocument();
    await act(async () => pending.resolve(json({}, 500))); expect(await screen.findByRole("alert")).toHaveTextContent("Unable to load or save incident data");
    handlers[base] = async () => json(incidentPage([])); await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByRole("heading", { name: "No incidents recorded" });
    await userEvent.selectOptions(screen.getByLabelText("Severity"), "CRITICAL");
    expect(screen.getByRole("heading", { name: "No incidents recorded" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Apply filters" })); await screen.findByRole("heading", { name: "No matching incidents" });
});
test("zero organisations avoids incident requests and offers existing onboarding", async () => {
    organisations = []; mount(); expect(await screen.findByRole("heading", { name: "Welcome to OpsPilot" })).toBeInTheDocument();
    expect(within(screen.getByRole("main")).getByRole("link", { name: "Create Organisation" })).toHaveAttribute("href", "/organisations/new"); expect(calls()).toHaveLength(0);
});
test.each([403, 404])("unavailable details (%s) never render cached content or a comment form", async status => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(incidentKeys.detail(ORG, INCIDENT), incidentFixture({ title: "Previously visible private title" }));
    handlers[`${base}/${INCIDENT}`] = async () => json({}, status); mount(`/incidents/${INCIDENT}`, client);
    await screen.findByRole("alert"); expect(screen.queryByText("Previously visible private title")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Investigation note" })).not.toBeInTheDocument(); expect(calls(`${base}/${INCIDENT}/events`)).toHaveLength(0);
});
test("list switching hides old data even with keepPreviousData and discards late responses", async () => {
    const late = deferred(); let oldSignal: AbortSignal | null | undefined;
    handlers[base] = (_url, options) => { oldSignal = options.signal; return late.promise; };
    mount(); await screen.findByText("Loading incidents…"); await userEvent.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), OTHER_ORG);
    await screen.findByRole("heading", { name: "No incidents recorded" }); expect(oldSignal?.aborted).toBe(true);
    await act(async () => late.resolve(json(incidentPage([incidentFixture({ title: "Late Alpha incident" })]))));
    expect(screen.queryByText("Late Alpha incident")).not.toBeInTheDocument(); expect(calls(otherBase).length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { name: "Incidents" }).parentElement).toHaveTextContent("Beta operations");
});
test("switching organisation clears filters, page and service deep-link scope", async () => {
    mount(`/incidents?serviceId=${SERVICE}`); await screen.findByRole("link", { name: "Payments availability incident" });
    expect(screen.getByLabelText("Affected service")).toHaveValue(SERVICE);
    await userEvent.selectOptions(screen.getByLabelText("Status"), "RESOLVED"); await userEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await screen.findByRole("link", { name: "Recovered outage" });
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), OTHER_ORG); await screen.findByRole("heading", { name: "No incidents recorded" });
    expect(screen.getByLabelText("Status")).toHaveValue(""); expect(screen.getByLabelText("Affected service")).toHaveValue("");
    expect(new URL(calls(otherBase).at(-1)![0]).searchParams.has("serviceId")).toBe(false);
});
test("a foreign list response is rejected and a delayed old timeline cannot appear in the active tenant", async () => {
    const late = deferred(); handlers[`${base}/${INCIDENT}/events`] = () => late.promise;
    handlers[`${otherBase}/${INCIDENT}`] = async () => json(incidentFixture());
    mount(`/incidents/${INCIDENT}`); await screen.findByRole("heading", { name: "Payments availability incident" });
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), OTHER_ORG);
    await screen.findByRole("alert"); expect(screen.queryByRole("heading", { name: "Payments availability incident" })).not.toBeInTheDocument();
    await act(async () => late.resolve(json(incidentPage([eventFixture({ details: "Late confidential evidence" })]))));
    expect(screen.queryByText("Late confidential evidence")).not.toBeInTheDocument(); expect(calls(`${otherBase}/${INCIDENT}/events`)).toHaveLength(0);
});
test("timeline is paginated chronologically with empty/error and retry feedback", async () => {
    let fail = true;
    handlers[`${base}/${INCIDENT}/events`] = async url => {
        if (fail) return json({}, 500);
        const page = Number(url.searchParams.get("page"));
        return json(incidentPage([eventFixture({ id: page ? SECOND_INCIDENT : EVENT, details: page ? "Later event" : "Earliest event" })], page, 20, 21));
    };
    mount(`/incidents/${INCIDENT}`); await screen.findByRole("alert"); fail = false;
    await userEvent.click(screen.getByRole("button", { name: "Try again" })); await screen.findByText("Earliest event");
    const pages = screen.getByRole("navigation", { name: "Timeline pages" }); await userEvent.click(within(pages).getByRole("button", { name: "Next" }));
    await screen.findByText("Later event"); expect(screen.queryByText("Earliest event")).not.toBeInTheDocument();
    handlers[`${base}/${INCIDENT}/events`] = async url => json(incidentPage([], Number(url.searchParams.get("page"))));
    await userEvent.click(screen.getByRole("button", { name: "Refresh timeline" })); await screen.findByText("No timeline events on this page.");
});
test("long untrusted titles/descriptions are rendered as text without invented update timestamps", async () => {
    records[ORG] = [incidentFixture({ title: "<svg onload=alert(1)>" + "x".repeat(150), description: "Long investigation\n" + "word".repeat(900) })];
    mount(`/incidents/${INCIDENT}`); expect(await screen.findByRole("heading", { level: 1, name: /^<svg/ })).toHaveTextContent("<svg onload=alert(1)>");
    expect(document.querySelector("h1 svg")).toBeNull(); expect(screen.queryByText("Last updated")).not.toBeInTheDocument();
    expect(screen.getByText(/Long investigation/)).toHaveClass("incident-description");
});
test("Incidents is available in the existing mobile drawer and closes it on navigation", async () => {
    mount(); await screen.findByRole("heading", { name: "Incidents" });
    await userEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = screen.getByRole("dialog", { name: "Navigation" });
    expect(within(drawer).getByRole("link", { name: "Incidents" })).toHaveAttribute("aria-current", "page");
    await userEvent.click(within(drawer).getByRole("link", { name: "Incidents" })); expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});
test.each(["OWNER", "ADMIN", "ENGINEER", "VIEWER"] as const)("real organisation-context %s role controls details without using identity-provider roles", async role => {
    organisations = [{ ...alpha, currentUserId: USER, currentUserRole: role }, beta];
    mount(`/incidents/${INCIDENT}`); await screen.findByRole("heading", { name: "Payments availability incident" });
    const write = role !== "VIEWER";
    expect(Boolean(screen.queryByRole("button", { name: "Acknowledged" }))).toBe(write);
    expect(Boolean(screen.queryByRole("textbox", { name: "Investigation note" }))).toBe(write);
    expect(Boolean(screen.queryByRole("textbox", { name: "Local user ID" }))).toBe(role === "OWNER" || role === "ADMIN");
});
test("organisation permissions are revalidated after revocation and old details are hidden", async () => {
    organisations = [{ ...alpha, currentUserId: USER, currentUserRole: "ENGINEER" }];
    handlers[`${base}/${INCIDENT}/status`] = async () => {
        organisations = [];
        handlers[`${base}/${INCIDENT}`] = async () => json({}, 404);
        return json({}, 403);
    };
    mount(`/incidents/${INCIDENT}`); await userEvent.click(await screen.findByRole("button", { name: "Acknowledged" }));
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm change" }));
    await screen.findByRole("heading", { name: "Welcome to OpsPilot" });
    expect(screen.queryByRole("heading", { name: "Payments availability incident" })).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Investigation note" })).not.toBeInTheDocument();
});
test("invalid incident and service links have actionable input errors rather than raw schema diagnostics", async () => {
    mount("/incidents/not-an-id"); expect(await screen.findByRole("alert")).toHaveTextContent("invalid identifier"); expect(calls(`${base}/not-an-id`)).toHaveLength(0);
});
