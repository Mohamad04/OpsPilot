import { StrictMode } from "react";
import { beforeEach, expect, test, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import userEvent from "@testing-library/user-event";
import ServiceDetailsPage from "../pages/ServiceDetailsPage";
import { getMonitoredService, enableMonitoredService, disableMonitoredService } from "../api/monitoredServiceApi";
import { ApiError } from "../api/client";
import type { MonitoredService } from "../types/MonitoredService";

vi.mock("../api/monitoredServiceApi", () => ({
    getMonitoredService: vi.fn(), enableMonitoredService: vi.fn(), disableMonitoredService: vi.fn(),
}));
const fixture = vi.hoisted(() => ({
    organisation: { currentOrganisation: { id: "organisation-123" } as { id: string } | null,
        organisationsPending: false, organisationsError: null as string | null },
}));
vi.mock("../hooks/useOrganisation", () => ({ useOrganisation: () => fixture.organisation }));

const service: MonitoredService = {
    id: "monitor-123", organisationId: "organisation-123", name: "Payment API",
    serviceType: "HTTP", environment: "PRODUCTION", owner: "Payments team", enabled: true,
    baseUrl: "https://payments.example.com", healthEndpoint: "/health",
};

beforeEach(() => {
    vi.resetAllMocks();
    fixture.organisation = { currentOrganisation: { id: service.organisationId }, organisationsPending: false, organisationsError: null };
    vi.mocked(getMonitoredService).mockResolvedValue({ ...service });
    vi.mocked(enableMonitoredService).mockResolvedValue({ ...service, enabled: true });
    vi.mocked(disableMonitoredService).mockResolvedValue({ ...service, enabled: false });
});

function renderPage(path = "/services/monitor-123") {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const tree = () => <StrictMode><QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}><Routes>
            <Route path="/services/:id" element={<ServiceDetailsPage />} />
            <Route path="/services" element={<h1>Services list</h1>} />
            <Route path="/missing-id" element={<ServiceDetailsPage />} />
        </Routes></MemoryRouter>
    </QueryClientProvider></StrictMode>;
    const view = render(tree());
    return { client, ...view, rerenderPage: () => view.rerender(tree()) };
}

async function openAction(name = "Disable service") {
    const user = userEvent.setup();
    const trigger = await screen.findByRole("button", { name });
    await waitFor(() => expect(trigger).toBeEnabled());
    await user.click(trigger);
    return { user, trigger, dialog: screen.getByRole("dialog", { name: `${name}?` }) };
}

test("shows a real service summary, information cards and configuration-only state", async () => {
    renderPage();
    const summary = await screen.findByRole("region", { name: service.name });
    expect(within(summary).getByRole("heading", { level: 1 })).toHaveTextContent(service.name);
    expect(within(summary).getByText("Enabled")).toBeInTheDocument();
    expect(within(summary).getByText("HTTP")).toBeInTheDocument();
    expect(within(summary).getByText("Production")).toBeInTheDocument();
    const overview = screen.getByRole("region", { name: "Service overview" });
    expect(overview).toHaveTextContent(service.owner);
    expect(overview).toHaveTextContent(service.id);
    const endpoints = screen.getByRole("region", { name: "Endpoints" });
    expect(endpoints).toHaveTextContent(service.baseUrl);
    expect(endpoints).toHaveTextContent(service.healthEndpoint);
    expect(endpoints).toHaveTextContent("Health monitoring is not yet available.");
    expect(screen.queryByText(/^(healthy|online)$/i)).not.toBeInTheDocument();
    expect(getMonitoredService).toHaveBeenCalledExactlyOnceWith(service.organisationId, service.id);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(disableMonitoredService).not.toHaveBeenCalled();
});

test.each(["DEVELOPMENT", "TEST", "STAGING", "PRODUCTION"] as const)("renders %s with a readable environment label", async (environment) => {
    vi.mocked(getMonitoredService).mockResolvedValue({ ...service, environment });
    renderPage();
    const summary = await screen.findByRole("region", { name: service.name });
    const label = environment[0] + environment.slice(1).toLowerCase();
    expect(within(summary).getByText(label)).toHaveTextContent(label);
});

test.each([true, false])("confirmation changes enabled=%s and invalidates both existing query keys", async (enabled) => {
    let current = { ...service, enabled };
    vi.mocked(getMonitoredService).mockImplementation(async () => current);
    const request = enabled ? vi.mocked(disableMonitoredService) : vi.mocked(enableMonitoredService);
    request.mockImplementation(async () => { current = { ...current, enabled: !enabled }; return current; });
    const { client } = renderPage();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    client.setQueryData(["monitored-services", "other-organisation"], []);
    const label = enabled ? "Disable service" : "Enable service";
    const { user, dialog } = await openAction(label);
    expect(request).not.toHaveBeenCalled();
    expect(dialog).toHaveAccessibleDescription(new RegExp(service.name));
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
    await user.click(within(dialog).getByRole("button", { name: label }));
    expect(await screen.findByRole("status")).toHaveTextContent(`Service ${enabled ? "disabled" : "enabled"} successfully.`);
    expect(request).toHaveBeenCalledExactlyOnceWith(service.organisationId, service.id);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["monitored-service", service.organisationId, service.id] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["monitored-services", service.organisationId] });
    expect(client.getQueryState(["monitored-services", "other-organisation"])?.isInvalidated).toBe(false);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    const next = await screen.findByRole("button", { name: enabled ? "Enable service" : "Disable service" });
    await waitFor(() => expect(next).toHaveFocus());
    expect(getMonitoredService).toHaveBeenCalledTimes(2);
    expect(document.body.style.overflow).toBe("");
});

test.each(["button", "Escape", "backdrop", "native cancel"])("%s cancels confirmation without mutating and returns focus", async (method) => {
    renderPage();
    const { user, trigger, dialog } = await openAction();
    expect(document.body.style.overflow).toBe("hidden");
    if (method === "button") await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    if (method === "Escape") await user.keyboard("{Escape}");
    if (method === "backdrop") fireEvent.click(dialog);
    if (method === "native cancel") fireEvent(dialog, new Event("cancel", { cancelable: true }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(enableMonitoredService).not.toHaveBeenCalled();
    expect(disableMonitoredService).not.toHaveBeenCalled();
    expect(document.body.style.overflow).toBe("");
});

test("pending confirmation prevents duplicate submits and dismissal until the request settles", async () => {
    let complete!: (data: MonitoredService) => void;
    vi.mocked(disableMonitoredService).mockReturnValue(new Promise((resolve) => { complete = resolve; }));
    renderPage();
    const { user, trigger, dialog } = await openAction();
    await user.click(within(dialog).getByRole("button", { name: "Disable service" }));
    const saving = within(dialog).getByRole("button", { name: "Saving..." });
    expect(saving).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(within(dialog).getByRole("status")).toHaveTextContent("Disabling service... Please wait.");
    expect(dialog.querySelector("form")).toHaveAttribute("aria-busy", "true");
    expect(trigger).toBeDisabled();
    await user.dblClick(saving);
    fireEvent.submit(dialog.querySelector("form")!);
    fireEvent.keyDown(dialog, { key: "Escape" });
    expect(dialog).toHaveAttribute("open");
    expect(disableMonitoredService).toHaveBeenCalledTimes(1);
    vi.mocked(getMonitoredService).mockResolvedValue({ ...service, enabled: false });
    await act(async () => complete({ ...service, enabled: false }));
    expect(await screen.findByRole("status")).toHaveTextContent("Service disabled successfully.");
});

test.each([
    new ApiError(401, "Authentication required. Sign in again."),
    new ApiError(403, "You do not have permission to perform this action in this organisation."),
    new Error("Could not disable monitored service"),
])("failed action preserves the actual state and shows an accessible error: %s", async (error) => {
    vi.mocked(disableMonitoredService).mockRejectedValue(error);
    renderPage();
    const { user, dialog, trigger } = await openAction();
    await user.click(within(dialog).getByRole("button", { name: "Disable service" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent(error.message);
    expect(within(screen.getByRole("region", { name: service.name })).getByText("Enabled")).toBeInTheDocument();
    expect(screen.queryByText(/successfully/)).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeEnabled();
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(trigger).toHaveFocus();
    await user.click(trigger);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

test("an action can be retried after an API error", async () => {
    vi.mocked(disableMonitoredService).mockRejectedValueOnce(new Error("Temporary failure"));
    renderPage();
    const { user, dialog } = await openAction();
    await user.click(within(dialog).getByRole("button", { name: "Disable service" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Temporary failure");
    vi.mocked(getMonitoredService).mockResolvedValue({ ...service, enabled: false });
    await user.click(within(dialog).getByRole("button", { name: "Disable service" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Service disabled successfully.");
    expect(disableMonitoredService).toHaveBeenCalledTimes(2);
});

test("loading and failed detail queries offer feedback and a working retry", async () => {
    let fail!: (error: Error) => void;
    vi.mocked(getMonitoredService).mockReturnValueOnce(new Promise((_resolve, reject) => { fail = reject; }));
    renderPage();
    expect(screen.getByRole("status")).toHaveTextContent("Loading service...");
    await act(async () => fail(new Error("Could not get monitored service")));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not get monitored service");
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("heading", { name: service.name })).toBeInTheDocument();
});

test("long names, owners and endpoint values are rendered without losing data", async () => {
    const longService = { ...service, name: "VeryLongServiceName".repeat(8), owner: "Owner".repeat(24),
        baseUrl: `https://example.com/${"long-path".repeat(180)}`, healthEndpoint: `/${"health".repeat(200)}` };
    vi.mocked(getMonitoredService).mockResolvedValue(longService);
    renderPage();
    expect(await screen.findByRole("heading", { level: 1, name: longService.name })).toBeInTheDocument();
    expect(screen.getByText(longService.owner)).toBeInTheDocument();
    expect(screen.getByText(longService.baseUrl)).toBeInTheDocument();
    expect(screen.getByText(longService.healthEndpoint)).toBeInTheDocument();
    const { dialog } = await openAction();
    expect(dialog).toHaveAccessibleDescription(new RegExp(longService.name));
});

test("tenant switching closes confirmation and uses only the newly selected tenant for subsequent actions", async () => {
    const { rerenderPage } = renderPage();
    await openAction();
    fixture.organisation.currentOrganisation = { id: "other-organisation" };
    vi.mocked(getMonitoredService).mockResolvedValue({ ...service, organisationId: "other-organisation", name: "Other service" });
    rerenderPage();
    await screen.findByRole("heading", { name: "Other service" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe("");
    expect(disableMonitoredService).not.toHaveBeenCalled();
    const { user, dialog } = await openAction();
    await user.click(within(dialog).getByRole("button", { name: "Disable service" }));
    await waitFor(() => expect(disableMonitoredService).toHaveBeenCalledExactlyOnceWith("other-organisation", service.id));
});

test("a background state change invalidates an open confirmation", async () => {
    const { client } = renderPage();
    const { dialog } = await openAction();
    act(() => client.setQueryData(["monitored-service", service.organisationId, service.id], { ...service, enabled: false }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The service state has changed");
    expect(within(dialog).getByRole("button", { name: "Disable service" })).toBeDisabled();
    fireEvent.submit(dialog.querySelector("form")!);
    expect(disableMonitoredService).not.toHaveBeenCalled();
});

test("a failed background refresh keeps existing details, blocks actions, and supports retry", async () => {
    const { client } = renderPage();
    await screen.findByRole("heading", { name: service.name });
    vi.mocked(getMonitoredService).mockRejectedValueOnce(new Error("Refresh failed"));
    await act(async () => { await client.invalidateQueries({ queryKey: ["monitored-service", service.organisationId, service.id] }); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Unable to refresh service details. Refresh failed");
    expect(screen.getByRole("heading", { name: service.name })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Disable service" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Retry refresh" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Disable service" })).toBeEnabled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

test("an in-flight action finishing after tenant switching invalidates only its original tenant", async () => {
    let complete!: (data: MonitoredService) => void;
    vi.mocked(disableMonitoredService).mockReturnValueOnce(new Promise((resolve) => { complete = resolve; }));
    const { client, rerenderPage } = renderPage();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    const { user, dialog } = await openAction();
    await user.click(within(dialog).getByRole("button", { name: "Disable service" }));
    fixture.organisation.currentOrganisation = { id: "other-organisation" };
    vi.mocked(getMonitoredService).mockResolvedValue({ ...service, organisationId: "other-organisation", name: "Other service" });
    rerenderPage();
    await screen.findByRole("heading", { name: "Other service" });
    await act(async () => complete({ ...service, enabled: false }));
    await waitFor(() => expect(invalidate).toHaveBeenCalledTimes(2));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["monitored-service", service.organisationId, service.id] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["monitored-services", service.organisationId] });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByText(/successfully/)).not.toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Other service" })).getByText("Enabled")).toBeInTheDocument();
});

test("unmounting an open confirmation restores existing body scrolling", async () => {
    document.body.style.overflow = "auto";
    const { unmount } = renderPage();
    await openAction();
    unmount();
    expect(document.body.style.overflow).toBe("auto");
    document.body.style.overflow = "";
});

test.each(["loading", "error", "missing"])("handles %s organisation context without requesting a service", async (state) => {
    fixture.organisation.currentOrganisation = null;
    fixture.organisation.organisationsPending = state === "loading";
    fixture.organisation.organisationsError = state === "error" ? "Organisation access denied" : null;
    renderPage();
    if (state === "loading") expect(screen.getByRole("status")).toHaveTextContent("Loading organisation...");
    if (state === "error") expect(screen.getByRole("alert")).toHaveTextContent("Organisation access denied");
    if (state === "missing") expect(screen.getByRole("heading")).toHaveTextContent("No organisation found.");
    expect(getMonitoredService).not.toHaveBeenCalled();
});

test("missing route ID is handled without issuing an API request", () => {
    renderPage("/missing-id");
    expect(screen.getByRole("alert")).toHaveTextContent("Service ID is missing.");
    expect(getMonitoredService).not.toHaveBeenCalled();
});

test("Back to services retains the existing list route", async () => {
    renderPage();
    await screen.findByRole("heading", { name: service.name });
    await userEvent.click(screen.getByRole("link", { name: "Back to services" }));
    expect(screen.getByRole("heading", { name: "Services list" })).toBeInTheDocument();
});
