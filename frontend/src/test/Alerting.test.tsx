import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { keepPreviousData, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import App from "../App";
import { OrganisationProvider } from "../context/OrganisationProvider";
import { getAccessToken } from "../auth/auth";
import { alertKeys } from "../hooks/useAlerts";
import type { Alert, AlertRule, AlertRuleInput } from "../types/Alert";
import type { MonitoredService } from "../types/MonitoredService";
import { alertFixture, alertPage, defaultAlertRule } from "./alertFixtures";
import { unknownHealth } from "./healthFixtures";

vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn(), logout: vi.fn() }));
vi.mock("../auth/useAuth", () => ({ useAuth: () => ({ status: "authenticated", user: { displayName: "Alert tester" } }) }));
const alpha = { id: "alpha", name: "Alpha team", slug: "alpha", status: "ACTIVE", createdAt: "2026-10-27" };
const beta = { ...alpha, id: "beta", name: "Beta team", slug: "beta" };
const service: MonitoredService = { id: "service", organisationId: "alpha", name: "Payments API", serviceType: "HTTP",
    environment: "PRODUCTION", enabled: true, baseUrl: "https://fixture.example.test", healthEndpoint: "/health", owner: "Team" };
const betaService = { ...service, id: "beta-service", organisationId: "beta", name: "Beta API" };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const fetchMock = vi.fn<(url: string, options: RequestInit) => Promise<Response>>();
const clients: QueryClient[] = [];
type Handler = (url: URL, options: RequestInit) => Promise<Response>;
let handlers: Record<string, Handler>;
let records: Record<string, Alert[]>;
let rule: AlertRule;
let services: MonitoredService[];
const collection = "/api/organisations/alpha/alerts";
const rulePath = "/api/organisations/alpha/monitored-services/service/alert-rule";

beforeEach(() => {
    vi.clearAllMocks(); vi.stubGlobal("fetch", fetchMock); vi.mocked(getAccessToken).mockResolvedValue("alert-ui-token");
    handlers = {}; services = [service]; rule = defaultAlertRule("alpha", "service");
    records = { alpha: [alertFixture(), { ...alertFixture("alpha", "service", "RESOLVED", "alert-2"), severity: "LOW" }], beta: [] };
    fetchMock.mockImplementation(async (url, options) => {
        expect(new Headers(options.headers).get("Authorization")).toBe("Bearer alert-ui-token");
        const parsed = new URL(url); const path = parsed.pathname;
        if (handlers[path]) return handlers[path](parsed, options);
        if (path === "/api/organisations") return json([alpha, beta]);
        const match = path.match(/^\/api\/organisations\/([^/]+)(.*)$/);
        if (!match) throw new Error(`Unexpected fixture URL: ${url}`);
        const [, org, suffix] = match; const items = org === "alpha" ? services : [betaService];
        if (suffix === "/monitored-services") return json(items);
        if (suffix === "/alerts/summary") {
            const all = records[org]; const active = all.filter((entry) => entry.status === "ACTIVE");
            return json({ totalAlerts: all.length, activeAlerts: active.length, resolvedAlerts: all.length - active.length,
                activeBySeverity: Object.fromEntries(["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((severity) => [severity, active.filter((entry) => entry.severity === severity).length])) });
        }
        if (suffix.startsWith("/alerts/")) return json(records[org].find((alert) => alert.id === suffix.split("/")[2]) ?? {},
            records[org].some((alert) => alert.id === suffix.split("/")[2]) ? 200 : 404);
        if (suffix === "/alerts" || suffix.endsWith("/alerts")) {
            const selectedService = parsed.searchParams.get("serviceId") ?? (suffix.startsWith("/monitored-services/") ? suffix.split("/")[2] : null);
            const found = records[org].filter((alert) => (!selectedService || alert.monitoredServiceId === selectedService)
                && (!parsed.searchParams.get("status") || alert.status === parsed.searchParams.get("status"))
                && (!parsed.searchParams.get("severity") || alert.severity === parsed.searchParams.get("severity"))
                && (!parsed.searchParams.get("from") || Date.parse(alert.firstSeenAt) >= Date.parse(parsed.searchParams.get("from")!))
                && (!parsed.searchParams.get("to") || Date.parse(alert.firstSeenAt) < Date.parse(parsed.searchParams.get("to")!)))
                .sort((a, b) => Date.parse(b.firstSeenAt) - Date.parse(a.firstSeenAt) || b.id.localeCompare(a.id));
            const page = Number(parsed.searchParams.get("page") ?? 0), size = Number(parsed.searchParams.get("size") ?? 20);
            return json(alertPage(found.slice(page * size, (page + 1) * size), page, size, found.length));
        }
        const id = suffix.split("/")[2]; const found = items.find((entry) => entry.id === id);
        if (!found) return json({}, 404);
        if (suffix.endsWith("/alert-rule")) {
            if (options.method === "PUT") rule = { ...rule, ...JSON.parse(String(options.body)), id: "saved-rule",
                createdAt: "2026-10-27T12:00:00Z", updatedAt: "2026-10-27T12:00:00Z" };
            return json(rule);
        }
        if (suffix.endsWith("/health/latest")) return json(unknownHealth(org, id));
        if (suffix.endsWith("/health/history")) return json([]);
        return json(found);
    });
});
afterEach(() => { clients.splice(0).forEach((client) => client.clear()); vi.unstubAllGlobals(); });
function mount(path = "/alerts", client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false, placeholderData: keepPreviousData } } })) {
    clients.push(client);
    render(<StrictMode><QueryClientProvider client={client}><OrganisationProvider><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></OrganisationProvider></QueryClientProvider></StrictMode>);
    return client;
}
const history = () => screen.getByRole("region", { name: "Alert history" });
const configuration = () => screen.getByRole("region", { name: "Alert configuration" });
const resultSection = () => screen.getByRole("region", { name: "Recorded alerts" });
const calls = (path = collection) => fetchMock.mock.calls.filter(([url]) => new URL(url).pathname === path);
function deferred() { let resolve!: (response: Response) => void; const promise = new Promise<Response>((done) => { resolve = done; }); return { promise, resolve }; }

test("registry shows real active/resolved alerts, names, category, counts and details navigation", async () => {
    mount();
    const details = await screen.findByRole("link", { name: "View alert alert-1" });
    expect(within(resultSection()).getByText("Active")).toBeInTheDocument();
    expect(within(resultSection()).getByText("Resolved")).toBeInTheDocument();
    expect(within(resultSection()).getAllByText("Payments API")).toHaveLength(2);
    expect(within(resultSection()).getAllByText("HTTP availability")).toHaveLength(2);
    expect(screen.getByRole("group", { name: "Active alerts" })).toHaveTextContent("1");
    await userEvent.click(details);
    expect(await screen.findByRole("heading", { name: "HTTP availability alert" })).toBeInTheDocument();
    expect(screen.getByText("health-1")).toBeInTheDocument();
    expect(screen.getByText("503")).toBeInTheDocument(); expect(screen.getByText("25 ms")).toBeInTheDocument();
    expect(screen.getByText(/Incident timelines and root-cause analysis are not implemented/)).toBeInTheDocument();
});
test("resolved details expose actual resolution evidence and recovery threshold", async () => {
    mount("/alerts/alert-2");
    await screen.findByRole("heading", { name: "HTTP availability alert" });
    expect(screen.getByText("Resolved")).toBeInTheDocument(); expect(screen.getByText("health-5")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Payments API" })).toHaveAttribute("href", "/services/service");
});
test("empty registry has useful feedback and no fabricated counts", async () => {
    records.alpha = []; mount();
    expect(await screen.findByRole("heading", { name: "No alerts found" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Active alerts" })).toHaveTextContent("0");
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
});
test("status, severity and service filters use server queries and clear resets pagination", async () => {
    mount(); await screen.findByRole("link", { name: "View alert alert-1" });
    await userEvent.selectOptions(screen.getByLabelText("Status"), "RESOLVED");
    await userEvent.selectOptions(screen.getByLabelText("Severity"), "LOW");
    await userEvent.selectOptions(screen.getByLabelText("Service", { selector: "select" }), "service");
    await userEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => expect(screen.queryByRole("link", { name: "View alert alert-1" })).not.toBeInTheDocument());
    expect(await screen.findByRole("link", { name: "View alert alert-2" })).toBeInTheDocument();
    const params = new URL(calls().at(-1)![0]).searchParams;
    expect(params.get("status")).toBe("RESOLVED"); expect(params.get("severity")).toBe("LOW"); expect(params.get("serviceId")).toBe("service");
    await userEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    await screen.findByRole("link", { name: "View alert alert-1" }); expect(screen.getByLabelText("Status")).toHaveValue("");
});
test("date filters convert local time to instants and reject reversed ranges before requests", async () => {
    mount(); await screen.findByRole("link", { name: "View alert alert-1" });
    const from = screen.getByLabelText("From (inclusive)"), to = screen.getByLabelText("To (exclusive)");
    fireEvent.change(from, { target: { value: "2026-10-28T10:00" } }); fireEvent.change(to, { target: { value: "2026-10-27T10:00" } });
    const before = calls().length; await userEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("From date must be earlier"); expect(calls()).toHaveLength(before);
    fireEvent.change(from, { target: { value: "2026-10-26T10:00" } });
    await userEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => expect(calls().length).toBeGreaterThan(before));
    expect(new URL(calls().at(-1)![0]).searchParams.get("from")).toBe(new Date("2026-10-26T10:00").toISOString());
});
test("next and previous request server pages rather than slicing an incomplete client list", async () => {
    records.alpha = Array.from({ length: 25 }, (_, index) => alertFixture("alpha", "service", "RESOLVED", `alert-${String(index).padStart(2, "0")}`));
    mount(); await screen.findByText(/25 alerts · Page 1 of 2/);
    expect(within(resultSection()).getAllByRole("link", { name: /View alert/ })).toHaveLength(20);
    await userEvent.click(screen.getByRole("button", { name: "Next" })); await screen.findByText(/Page 2 of 2/);
    expect(new URL(calls().at(-1)![0]).searchParams.get("page")).toBe("1");
    expect(within(resultSection()).getAllByRole("link", { name: /View alert/ })).toHaveLength(5);
    await userEvent.click(screen.getByRole("button", { name: "Previous" })); await screen.findByText(/Page 1 of 2/);
});
test("loading, error and explicit retry hide cached rows after denied refresh", async () => {
    const pending = deferred(); handlers[collection] = () => pending.promise; mount();
    await screen.findByText("Loading alerts…"); await act(async () => pending.resolve(json({}, 500)));
    expect(await within(resultSection()).findByRole("alert")).toHaveTextContent("Unable to load");
    delete handlers[collection]; await userEvent.click(within(resultSection()).getByRole("button", { name: "Try again" }));
    await screen.findByRole("link", { name: "View alert alert-1" });
    handlers[collection] = async () => json({}, 403); await userEvent.click(screen.getByRole("button", { name: "Refresh alerts" }));
    await within(resultSection()).findByRole("alert"); expect(screen.queryByRole("link", { name: "View alert alert-1" })).not.toBeInTheDocument();
});
test.each([403, 404])("details hide denied cached records on HTTP %i", async (status) => {
    const client = new QueryClient(); client.setQueryData(alertKeys.detail("alpha", "alert-1"), alertFixture());
    handlers[`${collection}/alert-1`] = async () => json({}, status); mount("/alerts/alert-1", client);
    await screen.findByRole("alert"); expect(screen.queryByText("health-1")).not.toBeInTheDocument();
});
test("cross-tenant alert responses are rejected before presentation", async () => {
    handlers[collection] = async () => json(alertPage([alertFixture("beta")])); mount();
    expect(await within(await screen.findByRole("region", { name: "Recorded alerts" })).findByRole("alert")).toHaveTextContent("does not match");
    expect(screen.queryByRole("link", { name: "View alert alert-1" })).not.toBeInTheDocument();
});
test("switching tenant cancels pending requests, resets filters and ignores late previous-tenant data", async () => {
    mount("/alerts?serviceId=service"); await screen.findByRole("link", { name: "View alert alert-1" });
    const pending = deferred(); handlers[collection] = () => pending.promise;
    await userEvent.click(screen.getByRole("button", { name: "Refresh alerts" }));
    await waitFor(() => expect(calls().at(-1)![1].signal).toBeDefined());
    const signal = calls().at(-1)![1].signal;
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), "beta");
    await screen.findByRole("heading", { name: "No alerts found" }); expect(signal?.aborted).toBe(true);
    expect(screen.getByLabelText("Service", { selector: "select" })).toHaveValue("");
    await act(async () => pending.resolve(json(alertPage([alertFixture()]))));
    expect(screen.queryByRole("link", { name: "View alert alert-1" })).not.toBeInTheDocument();
});
test("summary follows organisation switching and delayed responses cannot replace active-tenant counts", async () => {
    const pending = deferred(); handlers[`${collection}/summary`] = () => pending.promise;
    records.beta = [{ ...alertFixture("beta", "beta-service", "ACTIVE", "beta-alert"), severity: "CRITICAL" }]; mount();
    await screen.findByText("Loading alert summary…");
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), "beta");
    expect(await screen.findByRole("group", { name: "Critical active alerts" })).toHaveTextContent("1");
    await act(async () => pending.resolve(json({ totalAlerts: 9, activeAlerts: 9, resolvedAlerts: 0, activeBySeverity: { LOW: 0, MEDIUM: 0, HIGH: 9, CRITICAL: 0 } })));
    expect(screen.getByRole("group", { name: "Active alerts" })).toHaveTextContent("1");
});
test("dashboard adds actual alert metrics while keeping service and health totals", async () => {
    mount("/"); expect(await screen.findByRole("group", { name: "Active alerts" })).toHaveTextContent("1");
    expect(screen.getByRole("group", { name: "Resolved alerts" })).toHaveTextContent("1");
    expect(screen.getByRole("group", { name: "Total registered services" })).toHaveTextContent("1");
    expect(await screen.findByRole("group", { name: "Latest UNKNOWN" })).toHaveTextContent("1");
    expect(screen.getByRole("link", { name: "View alerts" })).toHaveAttribute("href", "/alerts");
});
test("service details load default rule and bounded active/resolved history", async () => {
    mount("/services/service");
    expect(await screen.findByLabelText(/Consecutive failures/)).toHaveValue(3);
    expect(screen.getByLabelText(/Consecutive recoveries/)).toHaveValue(2);
    expect(screen.getByLabelText("Alerting enabled")).toBeChecked();
    expect(within(configuration()).getByText(/Effective defaults/)).toBeInTheDocument();
    expect(await within(history()).findByText("Active")).toBeInTheDocument();
    expect(within(history()).getByText("Resolved")).toBeInTheDocument();
    expect(new URL(calls("/api/organisations/alpha/monitored-services/service/alerts")[0][0]).searchParams.get("size")).toBe("5");
    await userEvent.click(screen.getByRole("link", { name: "View all service alerts" }));
    await screen.findByRole("heading", { name: "Alerts" }); expect(screen.getByLabelText("Service", { selector: "select" })).toHaveValue("service");
});
test("rule save waits for server confirmation and invalidates scoped caches without duplicate submissions", async () => {
    const client = mount("/services/service"); const invalidate = vi.spyOn(client, "invalidateQueries");
    const field = await screen.findByLabelText(/Consecutive failures/); await userEvent.clear(field); await userEvent.type(field, "5");
    const pending = deferred(); let request: AlertRuleInput | undefined;
    handlers[rulePath] = async (_url, options) => {
        if (options.method === "PUT") { request = JSON.parse(String(options.body)); return pending.promise; }
        return json(rule);
    };
    await userEvent.dblClick(screen.getByRole("button", { name: "Save alert configuration" }));
    expect(screen.queryByText("Alert configuration saved.")).not.toBeInTheDocument();
    await waitFor(() => expect(calls(rulePath).filter(([, options]) => options.method === "PUT")).toHaveLength(1));
    rule = { ...rule, ...request, id: "saved-rule", createdAt: "2026-10-27T12:00:00Z", updatedAt: "2026-10-27T12:00:00Z" };
    await act(async () => pending.resolve(json(rule)));
    expect(await screen.findByText("Alert configuration saved.")).toBeInTheDocument();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: alertKeys.rule("alpha", "service") });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: alertKeys.organisation("alpha") });
    expect(client.getQueryData(["alert-rule-draft", "alpha", "service"])).toBeUndefined();
});
test.each(["0", "21", "1.5"])("rule threshold %s is rejected accessibly without PUT", async (value) => {
    mount("/services/service"); const field = await screen.findByLabelText(/Consecutive failures/);
    await userEvent.clear(field); await userEvent.type(field, value); await userEvent.click(screen.getByRole("button", { name: "Save alert configuration" }));
    expect(await within(configuration()).findByRole("alert")).toHaveTextContent("whole number between 1 and 20");
    expect(field).toHaveAttribute("aria-invalid", "true"); expect(calls(rulePath).some(([, options]) => options.method === "PUT")).toBe(false);
});
test("VIEWER write denial makes the rule read-only while preserving readable confirmed configuration", async () => {
    handlers[rulePath] = async (_url, options) => options.method === "PUT" ? json({}, 403) : json(rule);
    mount("/services/service"); const field = await screen.findByLabelText(/Consecutive failures/);
    await userEvent.clear(field); await userEvent.type(field, "5"); await userEvent.click(screen.getByRole("button", { name: "Save alert configuration" }));
    await screen.findByText(/read-only for your account/); expect(field).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save alert configuration" })).toBeDisabled();
    expect(screen.queryByText("Alert configuration saved.")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Discard changes" })); expect(field).toHaveValue(3);
});
test("network mutation errors preserve unsaved entries and never claim success", async () => {
    handlers[rulePath] = async (_url, options) => options.method === "PUT" ? json({}, 500) : json(rule);
    mount("/services/service"); const field = await screen.findByLabelText(/Consecutive failures/);
    await userEvent.clear(field); await userEvent.type(field, "4"); await userEvent.click(screen.getByRole("button", { name: "Save alert configuration" }));
    expect(await within(configuration()).findByRole("alert")).toHaveTextContent("Your changes were not saved");
    expect(field).toHaveValue(4); expect(screen.queryByText("Alert configuration saved.")).not.toBeInTheDocument();
});
test("unsaved drafts survive in-app navigation, warn before unload and can be discarded", async () => {
    mount("/services/service"); let field = await screen.findByLabelText(/Consecutive failures/);
    await userEvent.clear(field); await userEvent.type(field, "6");
    const beforeUnload = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(beforeUnload); expect(beforeUnload.defaultPrevented).toBe(true);
    await userEvent.click(screen.getByRole("link", { name: "View all service alerts" }));
    await screen.findByRole("link", { name: "View alert alert-1" });
    await userEvent.click(within(resultSection()).getAllByRole("link", { name: "Payments API" })[0]);
    field = await screen.findByLabelText(/Consecutive failures/); expect(field).toHaveValue(6);
    await userEvent.click(screen.getByRole("button", { name: "Discard changes" })); expect(field).toHaveValue(3);
    const clean = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(clean); expect(clean.defaultPrevented).toBe(false);
});
test("non-HTTP services never request or display HTTP alert configuration", async () => {
    services = [{ ...service, serviceType: "TCP" }]; mount("/services/service");
    await screen.findByRole("heading", { name: "Payments API" }); await within(history()).findByText("Active");
    expect(screen.queryByRole("region", { name: "Alert configuration" })).not.toBeInTheDocument(); expect(calls(rulePath)).toHaveLength(0);
});

test("summary retry and manual refresh recover from errors without showing cached counts", async () => {
    handlers[`${collection}/summary`] = async () => json({}, 500); mount("/");
    const overview = await screen.findByRole("region", { name: "Alert overview" });
    expect(await within(overview).findByRole("alert")).toHaveTextContent("Unable to load");
    expect(within(overview).queryByRole("group", { name: "Active alerts" })).not.toBeInTheDocument();
    delete handlers[`${collection}/summary`]; await userEvent.click(within(overview).getByRole("button", { name: "Try again" }));
    await within(overview).findByRole("group", { name: "Active alerts" });
    handlers[`${collection}/summary`] = async () => json({}, 403);
    await userEvent.click(screen.getByRole("button", { name: "Refresh alert summary" }));
    await within(overview).findByRole("alert"); expect(within(overview).queryByRole("group", { name: "Active alerts" })).not.toBeInTheDocument();
});

test("a late rule save remains scoped to its original organisation after switching", async () => {
    const client = mount("/services/service"); const field = await screen.findByLabelText(/Consecutive failures/);
    await userEvent.clear(field); await userEvent.type(field, "4");
    const pending = deferred(); handlers[rulePath] = async (_url, options) => options.method === "PUT" ? pending.promise : json(rule);
    await userEvent.click(screen.getByRole("button", { name: "Save alert configuration" }));
    await waitFor(() => expect(calls(rulePath).some(([, options]) => options.method === "PUT")).toBe(true));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), "beta");
    await screen.findByRole("heading", { name: "Unable to load service" });
    rule = { ...rule, consecutiveFailures: 4, id: "saved", createdAt: "2026-10-27T12:00:00Z", updatedAt: "2026-10-27T12:00:00Z" };
    await act(async () => pending.resolve(json(rule)));
    await waitFor(() => expect(client.getQueryData(alertKeys.rule("alpha", "service"))).toEqual(rule));
    expect(client.getQueryData(alertKeys.rule("beta", "service"))).toBeUndefined();
    expect(screen.queryByText("Alert configuration saved.")).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Alert configuration" })).not.toBeInTheDocument();
});

test("a denied rule refresh hides cached configuration and never falls back to fabricated defaults", async () => {
    const client = mount("/services/service"); await screen.findByLabelText(/Consecutive failures/);
    handlers[rulePath] = async () => json({}, 403);
    await act(async () => { await client.invalidateQueries({ queryKey: alertKeys.rule("alpha", "service") }); });
    expect(await within(configuration()).findByRole("alert")).toHaveTextContent("permission");
    expect(screen.queryByLabelText(/Consecutive failures/)).not.toBeInTheDocument();
});

test("drafts never cross organisations and detect server changes before another save", async () => {
    const client = mount("/services/service"); const field = await screen.findByLabelText(/Consecutive failures/);
    await userEvent.clear(field); await userEvent.type(field, "6");
    expect(client.getQueryData(["alert-rule-draft", "beta", "service"])).toBeUndefined();
    rule = { ...rule, consecutiveFailures: 7, id: "changed-rule", createdAt: "2026-10-27T12:00:00Z", updatedAt: "2026-10-27T12:01:00Z" };
    await act(async () => { await client.invalidateQueries({ queryKey: alertKeys.rule("alpha", "service") }); });
    expect(await within(configuration()).findByRole("alert")).toHaveTextContent("server configuration has changed");
    expect(field).toHaveValue(6); expect(screen.getByRole("button", { name: "Save alert configuration" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Discard changes" })); expect(field).toHaveValue(7);
});
