import { StrictMode } from "react";
import { beforeEach, expect, test, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import userEvent from "@testing-library/user-event";
import ServicesPage from "../pages/ServicesPage";
import { getMonitoredServices } from "../api/monitoredServiceApi";
import type { MonitoredService } from "../types/MonitoredService";

vi.mock("../api/monitoredServiceApi", () => ({ getMonitoredServices: vi.fn() }));
const fixture = vi.hoisted(() => ({ organisation: {
    currentOrganisation: { id: "organisation-123", name: "Engineering" } as { id: string; name: string } | null,
    organisationsPending: false, organisationsError: null as string | null,
} }));
vi.mock("../hooks/useOrganisation", () => ({ useOrganisation: () => fixture.organisation }));
const payment: MonitoredService = {
    id: "payment", name: "Payments API", serviceType: "HTTP", environment: "PRODUCTION",
    organisationId: "organisation-123", owner: "Payments team", enabled: true,
    baseUrl: "https://payments.example.com", healthEndpoint: "/health",
};
const services: MonitoredService[] = [payment,
    { ...payment, id: "worker", name: "Job worker", serviceType: "TCP", environment: "STAGING", owner: "Platform", enabled: false, baseUrl: "https://worker.example.com" },
    { ...payment, id: "metrics", name: "Metrics", serviceType: "SNMP", environment: "PRODUCTION", owner: "Platform", enabled: true, baseUrl: "https://metrics.example.com" },
];
beforeEach(() => {
    vi.clearAllMocks();
    fixture.organisation = { currentOrganisation: { id: "organisation-123", name: "Engineering" }, organisationsPending: false, organisationsError: null };
    vi.mocked(getMonitoredServices).mockResolvedValue(services);
});

function renderPage(created = false) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
    const tree = () => <StrictMode><QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[{ pathname: "/services", state: created ? { createdOrganisationId: "organisation-123" } : undefined }]}><Routes>
            <Route path="/services" element={<ServicesPage />} />
            <Route path="/services/new" element={<h1>Registration page</h1>} />
            <Route path="/services/:id" element={<h1>Service details page</h1>} />
        </Routes></MemoryRouter>
    </QueryClientProvider></StrictMode>;
    const view = render(tree());
    return { client, ...view, rerenderPage: () => view.rerender(tree()) };
}

async function registry() { return screen.findByRole("table"); }
function serviceLinks() { return within(screen.getByRole("table")).getAllByRole("link"); }

test("loads the selected organisation's real registry with detail links and configuration badges", async () => {
    renderPage();
    const table = await registry();
    expect(getMonitoredServices).toHaveBeenCalledExactlyOnceWith("organisation-123");
    expect(within(table).getByRole("link", { name: "Payments API" })).toHaveAttribute("href", "/services/payment");
    expect(within(table).getAllByText("Production")).toHaveLength(2);
    expect(within(table).getByText("Disabled")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Showing 1–3 of 3 services");
    expect(screen.getByText(/Health monitoring is not yet available/)).toBeInTheDocument();
    expect(screen.queryByText(/^(healthy|online)$/i)).not.toBeInTheDocument();
    expect(within(table).getAllByRole("columnheader")).toHaveLength(5);
    expect(within(table).getAllByRole("row")).toHaveLength(4);
});

test.each([
    ["  PAYMENTS  ", "Payments API"], ["platform", "Job worker"], ["metrics.example.com", "Metrics"], ["tcp", "Job worker"],
])("searches locally for %s without another API request", async (term, result) => {
    renderPage();
    await registry();
    await userEvent.type(screen.getByRole("searchbox", { name: "Search services" }), term);
    expect(within(screen.getByRole("table")).getByRole("link", { name: result })).toBeInTheDocument();
    expect(getMonitoredServices).toHaveBeenCalledTimes(1);
    if (term !== "platform") expect(serviceLinks()).toHaveLength(1);
});

test("environment, type and enabled filters combine and can all be cleared", async () => {
    renderPage();
    await registry();
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText("Environment"), "PRODUCTION");
    await user.selectOptions(screen.getByLabelText("Service type"), "SNMP");
    await user.selectOptions(screen.getByLabelText("Status"), "enabled");
    expect(serviceLinks()).toHaveLength(1);
    expect(serviceLinks()[0]).toHaveTextContent("Metrics");
    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(serviceLinks()).toHaveLength(3);
    expect(screen.getByLabelText("Environment")).toHaveValue("");
    expect(screen.getByLabelText("Service type")).toHaveValue("");
    expect(screen.getByLabelText("Status")).toHaveValue("");
    expect(getMonitoredServices).toHaveBeenCalledTimes(1);
});

test("the Disabled filter uses enabled=false and never reports health", async () => {
    renderPage();
    await registry();
    await userEvent.selectOptions(screen.getByLabelText("Status"), "disabled");
    expect(serviceLinks()).toHaveLength(1);
    expect(serviceLinks()[0]).toHaveTextContent("Job worker");
    expect(screen.getByRole("status")).toHaveTextContent("1 of 1 services");
});

test("no search matches is distinct from an empty registry and clear filters recovers", async () => {
    renderPage();
    await registry();
    const user = userEvent.setup();
    await user.type(screen.getByRole("searchbox"), "not-a-service");
    expect(screen.getByRole("heading", { name: "No services match your search" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("0 services found");
    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(serviceLinks()).toHaveLength(3);
});

test("large registries paginate locally and search resets to the first result page", async () => {
    vi.mocked(getMonitoredServices).mockResolvedValue(Array.from({ length: 63 }, (_, index) => ({ ...payment, id: `service-${index}`, name: `Service ${String(index + 1).padStart(2, "0")}` })));
    renderPage();
    await registry();
    expect(serviceLinks()).toHaveLength(25);
    const user = userEvent.setup();
    expect(screen.getByRole("button", { name: "Previous page" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(screen.getByRole("status")).toHaveTextContent("Showing 26–50 of 63 services");
    expect(serviceLinks()[0]).toHaveTextContent("Service 26");
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(serviceLinks()).toHaveLength(13);
    expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Previous page" }));
    await user.type(screen.getByRole("searchbox"), "Service 63");
    expect(serviceLinks()).toHaveLength(1);
    expect(serviceLinks()[0]).toHaveTextContent("Service 63");
    expect(screen.getByRole("status")).toHaveTextContent("Showing 1–1 of 1 services");
    expect(screen.queryByRole("navigation", { name: "Services pagination" })).not.toBeInTheDocument();
    expect(getMonitoredServices).toHaveBeenCalledTimes(1);
});

test("an empty registry explains the next step and links to existing registration", async () => {
    vi.mocked(getMonitoredServices).mockResolvedValue([]);
    renderPage();
    const empty = await screen.findByRole("region", { name: "No monitored services yet." });
    expect(screen.queryByRole("searchbox")).not.toBeInTheDocument();
    await userEvent.click(within(empty).getByRole("link", { name: "Add service" }));
    expect(screen.getByRole("heading", { name: "Registration page" })).toBeInTheDocument();
});

test("detail navigation remains available from the service name", async () => {
    renderPage();
    await registry();
    await userEvent.click(screen.getByRole("link", { name: "Payments API" }));
    expect(screen.getByRole("heading", { name: "Service details page" })).toBeInTheDocument();
});

test("loading and request errors show useful feedback and a working retry", async () => {
    let reject!: (error: Error) => void;
    vi.mocked(getMonitoredServices).mockReturnValueOnce(new Promise((_resolve, fail) => { reject = fail; }));
    renderPage();
    expect(screen.getByRole("status")).toHaveTextContent("Loading services...");
    await act(async () => reject(new Error("Could not retrieve monitored services.")));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not retrieve monitored services.");
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await registry()).toBeInTheDocument();
});

test("a failed background refresh preserves cached services and offers retry", async () => {
    const { client } = renderPage();
    await registry();
    vi.mocked(getMonitoredServices).mockRejectedValueOnce(new Error("Refresh unavailable"));
    await act(async () => { await client.invalidateQueries({ queryKey: ["monitored-services", "organisation-123"] }); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Refresh unavailable");
    expect(serviceLinks()).toHaveLength(3);
    await userEvent.click(screen.getByRole("button", { name: "Retry refresh" }));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
});

test("switching organisation resets filters and shows only the new tenant's services", async () => {
    const { rerenderPage } = renderPage();
    await registry();
    await userEvent.type(screen.getByRole("searchbox"), "Metrics");
    fixture.organisation.currentOrganisation = { id: "second", name: "Second team" };
    vi.mocked(getMonitoredServices).mockResolvedValue([{ ...payment, id: "second-service", organisationId: "second", name: "Second service" }]);
    rerenderPage();
    await screen.findByRole("link", { name: "Second service" });
    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(screen.queryByRole("link", { name: "Payments API" })).not.toBeInTheDocument();
    expect(getMonitoredServices).toHaveBeenLastCalledWith("second");
});

test("long values and unsafe-looking text remain plain readable service data", async () => {
    const long = { ...payment, name: "<script>" + "LongService".repeat(12), owner: "Owner".repeat(25), baseUrl: `https://example.com/${"path".repeat(450)}` };
    vi.mocked(getMonitoredServices).mockResolvedValue([long]);
    renderPage();
    await registry();
    expect(screen.getByRole("link", { name: long.name })).toHaveTextContent(long.name);
    expect(screen.getByText(long.owner)).toBeInTheDocument();
    expect(screen.getByText(long.baseUrl)).toBeInTheDocument();
    expect(screen.getByRole("table").querySelector("script")).toBeNull();
});

test("organisation creation feedback remains visible", async () => {
    renderPage(true);
    expect(screen.getByText('Organisation "Engineering" created successfully.')).toHaveAttribute("role", "status");
    await registry();
});

test.each(["loading", "error", "missing"])("handles %s organisation context without a service request", (state) => {
    fixture.organisation.currentOrganisation = null;
    fixture.organisation.organisationsPending = state === "loading";
    fixture.organisation.organisationsError = state === "error" ? "Organisation access denied" : null;
    renderPage();
    if (state === "loading") expect(screen.getByRole("status")).toHaveTextContent("Loading organisations...");
    if (state === "error") expect(screen.getByRole("alert")).toHaveTextContent("Organisation access denied");
    if (state === "missing") expect(screen.getByRole("heading")).toHaveTextContent("No organisation found.");
    expect(getMonitoredServices).not.toHaveBeenCalled();
});
