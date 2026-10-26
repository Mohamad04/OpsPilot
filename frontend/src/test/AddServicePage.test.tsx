import { StrictMode } from "react";
import { beforeEach, expect, test, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useParams } from "react-router";
import userEvent from "@testing-library/user-event";
import { createMonitoredService } from "../api/monitoredServiceApi";
import { ApiError } from "../api/client";
import AddServicePage from "../pages/AddServicePage";
import type { MonitoredService } from "../types/MonitoredService";

vi.mock("../api/monitoredServiceApi", () => ({ createMonitoredService: vi.fn() }));
const fixture = vi.hoisted(() => ({ organisation: {
    currentOrganisation: { id: "organisation-123", name: "Engineering" } as { id: string; name: string } | null,
    organisationsPending: false, organisationsError: null as string | null,
} }));
vi.mock("../hooks/useOrganisation", () => ({ useOrganisation: () => fixture.organisation }));
const created: MonitoredService = { id: "new-service", organisationId: "organisation-123", name: "Payments API", serviceType: "HTTP", environment: "PRODUCTION", baseUrl: "https://payments.example.com", healthEndpoint: "/health", enabled: true, owner: "Payments team" };
const request = { name: created.name, serviceType: created.serviceType, environment: created.environment, baseUrl: created.baseUrl, healthEndpoint: created.healthEndpoint, owner: created.owner };

beforeEach(() => {
    vi.clearAllMocks();
    fixture.organisation = { currentOrganisation: { id: "organisation-123", name: "Engineering" }, organisationsPending: false, organisationsError: null };
    vi.mocked(createMonitoredService).mockResolvedValue(created);
});
function CreatedRoute() { return <h1>Created service {useParams().id}</h1>; }
function renderPage() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const tree = () => <StrictMode><QueryClientProvider client={client}><MemoryRouter initialEntries={["/services/new"]}><Routes>
        <Route path="/services/new" element={<AddServicePage />} />
        <Route path="/services/:id" element={<CreatedRoute />} />
        <Route path="/services" element={<h1>Services list</h1>} />
    </Routes></MemoryRouter></QueryClientProvider></StrictMode>;
    const view = render(tree());
    return { client, ...view, rerenderPage: () => view.rerender(tree()) };
}
async function fillForm() {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Name"), created.name);
    await user.type(screen.getByLabelText("Owner"), created.owner);
    await user.selectOptions(screen.getByLabelText("Service type"), "HTTP");
    await user.selectOptions(screen.getByLabelText("Environment"), "PRODUCTION");
    await user.type(screen.getByLabelText("Base URL"), created.baseUrl);
    await user.type(screen.getByLabelText("Health Endpoint"), created.healthEndpoint);
    return user;
}

test("preserves all six required registration fields, grouping and default selections", () => {
    renderPage();
    expect(screen.getByRole("region", { name: "Service information" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Endpoints" })).toBeInTheDocument();
    for (const label of ["Name", "Owner", "Service type", "Environment", "Base URL", "Health Endpoint"]) {
        expect(screen.getByLabelText(label)).toBeRequired();
        expect(screen.getByLabelText(label)).toHaveAttribute("aria-invalid", "false");
    }
    expect(screen.getByText("All fields are required.")).toBeInTheDocument();
    expect(screen.getByLabelText("Service type")).toHaveValue("HTTP");
    expect(screen.getByLabelText("Environment")).toHaveValue("DEVELOPMENT");
    expect(screen.getByText("Engineering")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText(/Choose a recognisable name/)).not.toBeInTheDocument();
});

test("hints appear only on focus; blur errors persist until corrected and stay associated with the field", async () => {
    renderPage();
    const user = userEvent.setup();
    const name = screen.getByLabelText("Name");
    await user.click(name);
    expect(screen.getByText(/Choose a recognisable name/)).toBeInTheDocument();
    expect(name).toHaveAttribute("aria-describedby", "service-name-feedback");
    await user.tab();
    expect(await screen.findByText("Name must not be blank.")).toHaveAttribute("role", "alert");
    expect(name).toHaveAttribute("aria-invalid", "true");
    await user.click(name);
    await user.type(name, " ");
    expect(screen.getByText("Name must not be blank.")).toBeInTheDocument();
    await user.type(name, "API");
    await waitFor(() => expect(name).toHaveAttribute("aria-invalid", "false"));
    await user.tab();
    expect(screen.queryByText(/Choose a recognisable name/)).not.toBeInTheDocument();
    expect(screen.queryByText("Name must not be blank.")).not.toBeInTheDocument();
    expect(name).not.toHaveAttribute("aria-describedby");
    expect(createMonitoredService).not.toHaveBeenCalled();
});

test("submitting an untouched invalid form focuses the first invalid input and never posts", async () => {
    renderPage();
    await userEvent.click(screen.getByRole("button", { name: "Add service" }));
    expect(await screen.findByText("Name must not be blank.")).toBeInTheDocument();
    expect(screen.getByLabelText("Name")).toHaveFocus();
    expect(screen.getByLabelText("Owner")).toHaveAttribute("aria-invalid", "true");
    expect(createMonitoredService).not.toHaveBeenCalled();
});

test.each([
    ["Name", "a".repeat(121), "Name must not exceed 120 characters."],
    ["Owner", "a".repeat(101), "Owner must not exceed 100 characters."],
    ["Base URL", "javascript:alert(1)", "Base URL must be a valid HTTP/HTTPS URL"],
    ["Base URL", `https://example.com/${"a".repeat(2048)}`, "Base URL must not exceed 2048 characters."],
    ["Health Endpoint", "invalid/health", "Health endpoint must start with '/'"],
    ["Health Endpoint", "/" + "a".repeat(2048), "Health endpoint must not exceed 2048 characters."],
])("preserves existing validation for %s", async (label, value, message) => {
    renderPage();
    const field = screen.getByLabelText(label);
    fireEvent.focus(field);
    fireEvent.change(field, { target: { value } });
    fireEvent.blur(field);
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(field).toHaveAttribute("aria-invalid", "true");
    expect(createMonitoredService).not.toHaveBeenCalled();
});

test("valid registration preserves the request, invalidates the selected organisation and navigates to details", async () => {
    const { client } = renderPage();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    const user = await fillForm();
    await user.click(screen.getByRole("button", { name: "Add service" }));
    expect(await screen.findByRole("heading", { name: "Created service new-service" })).toBeInTheDocument();
    expect(createMonitoredService).toHaveBeenCalledExactlyOnceWith("organisation-123", request);
    expect(invalidate).toHaveBeenCalledExactlyOnceWith({ queryKey: ["monitored-services", "organisation-123"] });
});

test("pending submission disables controls and cancel, and rejects repeated form submits", async () => {
    let complete!: (data: MonitoredService) => void;
    vi.mocked(createMonitoredService).mockReturnValueOnce(new Promise((resolve) => { complete = resolve; }));
    renderPage();
    const user = await fillForm();
    const form = screen.getByRole("form", { name: "Register service" });
    await user.click(screen.getByRole("button", { name: "Add service" }));
    expect(await screen.findByRole("button", { name: "Adding service..." })).toBeDisabled();
    expect(screen.getByLabelText("Name")).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("Registering service. Please wait...");
    expect(form).toHaveAttribute("aria-busy", "true");
    fireEvent.submit(form);
    fireEvent.submit(form);
    await user.click(screen.getByRole("link", { name: "Cancel" }));
    expect(screen.getByRole("form", { name: "Register service" })).toBeInTheDocument();
    expect(createMonitoredService).toHaveBeenCalledTimes(1);
    await act(async () => complete(created));
    expect(await screen.findByRole("heading", { name: "Created service new-service" })).toBeInTheDocument();
});

test.each([
    new ApiError(401, "Authentication required. Sign in again."),
    new ApiError(403, "You do not have permission to perform this action in this organisation."),
    new Error("Could not create monitored service"),
])("submission failure preserves entries and displays the API error: %s", async (error) => {
    vi.mocked(createMonitoredService).mockRejectedValueOnce(error);
    renderPage();
    const user = await fillForm();
    await user.click(screen.getByRole("button", { name: "Add service" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(error.message);
    expect(screen.getByLabelText("Name")).toHaveValue(created.name);
    expect(screen.getByLabelText("Base URL")).toHaveValue(created.baseUrl);
    expect(screen.getByLabelText("Environment")).toHaveValue("PRODUCTION");
    expect(screen.getByRole("button", { name: "Add service" })).toBeEnabled();
    expect(screen.queryByRole("heading", { name: "Created service new-service" })).not.toBeInTheDocument();
});

test("a failed registration can be retried explicitly", async () => {
    vi.mocked(createMonitoredService).mockRejectedValueOnce(new Error("Temporary failure"));
    renderPage();
    const user = await fillForm();
    await user.click(screen.getByRole("button", { name: "Add service" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Temporary failure");
    await user.click(screen.getByRole("button", { name: "Add service" }));
    expect(await screen.findByRole("heading", { name: "Created service new-service" })).toBeInTheDocument();
    expect(createMonitoredService).toHaveBeenCalledTimes(2);
});

test("organisation switching resets the draft and binds the next request to the new organisation", async () => {
    const { rerenderPage } = renderPage();
    await fillForm();
    fixture.organisation.currentOrganisation = { id: "second", name: "Second team" };
    rerenderPage();
    expect(screen.getByLabelText("Name")).toHaveValue("");
    expect(screen.getByLabelText("Base URL")).toHaveValue("");
    expect(screen.getByText("Second team")).toBeInTheDocument();
    const user = await fillForm();
    await user.click(screen.getByRole("button", { name: "Add service" }));
    await waitFor(() => expect(createMonitoredService).toHaveBeenCalledExactlyOnceWith("second", request));
});

test("an old tenant's pending creation cannot navigate or overwrite the new tenant's draft", async () => {
    let complete!: (data: MonitoredService) => void;
    vi.mocked(createMonitoredService).mockReturnValueOnce(new Promise((resolve) => { complete = resolve; }));
    const { client, rerenderPage } = renderPage();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    const user = await fillForm();
    await user.click(screen.getByRole("button", { name: "Add service" }));
    fixture.organisation.currentOrganisation = { id: "second", name: "Second team" };
    rerenderPage();
    await user.type(screen.getByLabelText("Name"), "New tenant draft");
    await act(async () => complete(created));
    await waitFor(() => expect(invalidate).toHaveBeenCalledExactlyOnceWith({ queryKey: ["monitored-services", "organisation-123"] }));
    expect(screen.getByRole("form", { name: "Register service" })).toBeInTheDocument();
    expect(screen.getByLabelText("Name")).toHaveValue("New tenant draft");
    expect(screen.queryByRole("heading", { name: "Created service new-service" })).not.toBeInTheDocument();
});

test.each(["Cancel", "Back to services"])("%s returns to the existing list without creating a service", async (label) => {
    renderPage();
    await userEvent.click(screen.getByRole("link", { name: label }));
    expect(screen.getByRole("heading", { name: "Services list" })).toBeInTheDocument();
    expect(createMonitoredService).not.toHaveBeenCalled();
});

test.each(["loading", "error", "missing"])("handles %s organisation context without a registration form", (state) => {
    fixture.organisation.currentOrganisation = null;
    fixture.organisation.organisationsPending = state === "loading";
    fixture.organisation.organisationsError = state === "error" ? "Organisation access denied" : null;
    renderPage();
    if (state === "loading") expect(screen.getByRole("status")).toHaveTextContent("Loading organisations...");
    if (state === "error") expect(screen.getByRole("alert")).toHaveTextContent("Organisation access denied");
    if (state === "missing") expect(screen.getByRole("heading")).toHaveTextContent("Select an organisation");
    expect(screen.queryByRole("form")).not.toBeInTheDocument();
    expect(createMonitoredService).not.toHaveBeenCalled();
});
