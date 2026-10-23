import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, useLocation } from "react-router";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import App from "../App";
import { OrganisationProvider } from "../context/OrganisationProvider";
import { endSession, getAccessToken } from "../auth/auth";
import type { Organisation } from "../types/Organisation";

vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn(), logout: vi.fn() }));
vi.mock("../auth/useAuth", () => ({ useAuth: () => ({ user: { displayName: "Alex Example" } }) }));

const fetchMock = vi.fn();
const existing: Organisation = { id: "existing", name: "Existing team", slug: "existing-team", status: "ACTIVE", createdAt: "2026-10-23T10:00:00Z" };
const created: Organisation = { id: "created", name: "New team", slug: "new-team", status: "INACTIVE", createdAt: "2026-10-23T11:00:00Z" };
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
const creationRequests = () => fetchMock.mock.calls.filter(([, options]) => options?.method === "POST");
const listRequests = () => fetchMock.mock.calls.filter(([url, options]) => url.endsWith("/api/organisations") && options?.method !== "POST");
const serviceRequests = () => fetchMock.mock.calls.filter(([url]) => url.endsWith("/monitored-services"));

function respondWithOrganisations(organisations: Organisation[] = []) {
    fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
        if (options.method === "POST") return json(created, 201);
        if (url.endsWith("/api/organisations")) return json(organisations);
        if (url.endsWith("/monitored-services")) return json([]);
        throw new Error(`Unexpected test API URL: ${url}`);
    });
}

function Location() {
    return <output data-testid="location">{useLocation().pathname}</output>;
}

function renderApplication(path = "/") {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
    render(
        <QueryClientProvider client={client}>
            <OrganisationProvider>
                <MemoryRouter initialEntries={[path]}><App /><Location /></MemoryRouter>
            </OrganisationProvider>
        </QueryClientProvider>,
    );
    return client;
}

async function fillForm() {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Name"), "New team");
    await user.type(screen.getByLabelText("Slug"), "new-team");
    return user;
}

beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("fetch", fetchMock);
    vi.mocked(getAccessToken).mockResolvedValue("test-access-token");
    respondWithOrganisations();
});
afterEach(() => vi.unstubAllGlobals());

test("a provisioned user with zero organisations gets a friendly creation entry without service requests", async () => {
    renderApplication();
    expect(await screen.findByRole("heading", { name: "Welcome to OpsPilot" }, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Organisation" })).toBeDisabled();
    expect(serviceRequests()).toHaveLength(0);
    const user = userEvent.setup();
    await user.click(within(screen.getByRole("main")).getByRole("link", { name: "Create Organisation" }));
    expect(screen.getByLabelText("Name")).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/organisations/new");
    expect(screen.queryByRole("heading", { name: "Welcome to OpsPilot" })).not.toBeInTheDocument();
});

test("the creation page remains accessible directly, including a trailing slash", async () => {
    const client = renderApplication("/organisations/new/");
    await waitFor(() => expect(client.getQueryData(["organisations"])).toEqual([]));
    expect(screen.getByLabelText("Name")).toBeInTheDocument();
    expect(screen.queryByText("Welcome to OpsPilot")).not.toBeInTheDocument();
});

test("creation updates selector/cache, selects the new organisation and requests its services with a bearer token", async () => {
    const client = renderApplication("/organisations/new");
    await waitFor(() => expect(client.getQueryData(["organisations"])).toEqual([]));
    const user = await fillForm();
    await user.click(screen.getByRole("button", { name: "Create Organisation" }));
    expect(await screen.findByText('Organisation "New team" created successfully.')).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/services");
    expect(screen.getByRole("combobox", { name: "Organisation" })).toHaveValue(created.id);
    expect(screen.getByRole("option", { name: "New team" })).toBeInTheDocument();
    expect(client.getQueryData(["organisations"])).toEqual([created]);
    expect(listRequests()).toHaveLength(1);
    expect(creationRequests()).toHaveLength(1);
    const [, options] = creationRequests()[0];
    expect(options.headers.get("Authorization")).toBe("Bearer test-access-token");
    expect(options.headers.get("Content-Type")).toBe("application/json");
    expect(JSON.parse(options.body)).toEqual({ name: "New team", slug: "new-team" });
    await waitFor(() => expect(serviceRequests()).toHaveLength(1));
    expect(serviceRequests()[0][0]).toContain(`/api/organisations/${created.id}/monitored-services`);
    expect(serviceRequests()[0][1].headers.get("Authorization")).toBe("Bearer test-access-token");
});

test("pending creation disables duplicate submissions", async () => {
    let complete: (response: Response) => void = () => { throw new Error("No pending creation"); };
    fetchMock.mockImplementation(async (_url: string, options: RequestInit) => {
        if (options.method === "POST") return new Promise<Response>((resolve) => { complete = resolve; });
        return json([]);
    });
    renderApplication("/organisations/new");
    const user = await fillForm();
    await user.click(screen.getByRole("button", { name: "Create Organisation" }));
    const pending = await screen.findByRole("button", { name: "Creating organisation..." });
    expect(pending).toBeDisabled();
    await user.dblClick(pending);
    expect(creationRequests()).toHaveLength(1);
    await act(async () => complete(json(created, 201)));
    expect(await screen.findByText('Organisation "New team" created successfully.')).toBeInTheDocument();
});

test("existing members keep their selection across routes and keep prior organisations after creating another", async () => {
    const second = { ...existing, id: "second", name: "Second team", slug: "second-team" };
    respondWithOrganisations([existing, second]);
    const client = renderApplication("/services");
    expect(await screen.findByRole("option", { name: "Second team" })).toBeInTheDocument();
    const user = userEvent.setup();
    await user.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), second.id);
    await waitFor(() => expect(serviceRequests().some(([url]) => url.includes("/second/"))).toBe(true));
    await user.click(screen.getByRole("link", { name: "Create Organisation" }));
    expect(screen.getByRole("combobox", { name: "Organisation" })).toHaveValue(second.id);
    await fillForm();
    await user.click(screen.getByRole("button", { name: "Create Organisation" }));
    expect(await screen.findByText('Organisation "New team" created successfully.')).toBeInTheDocument();
    expect(client.getQueryData(["organisations"])).toEqual([existing, second, created]);
    expect(screen.getByRole("combobox", { name: "Organisation" })).toHaveValue(created.id);
    expect(listRequests()).toHaveLength(1);
});

test("an older in-flight list cannot overwrite a successfully created organisation", async () => {
    let completeList: (response: Response) => void = () => { throw new Error("No pending list"); };
    let listSignal: AbortSignal | undefined;
    fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
        if (options.method === "POST") return json(created, 201);
        if (url.endsWith("/api/organisations")) {
            listSignal = options.signal ?? undefined;
            return new Promise<Response>((resolve) => { completeList = resolve; });
        }
        return json([]);
    });
    const client = renderApplication("/organisations/new");
    const user = await fillForm();
    await user.click(screen.getByRole("button", { name: "Create Organisation" }));
    expect(await screen.findByText('Organisation "New team" created successfully.')).toBeInTheDocument();
    expect(listSignal?.aborted).toBe(true);
    await act(async () => completeList(json([])));
    expect(client.getQueryData(["organisations"])).toEqual([created]);
    expect(screen.getByRole("combobox", { name: "Organisation" })).toHaveValue(created.id);
});

test("empty or invalid fields are rejected locally without posting", async () => {
    renderApplication("/organisations/new");
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Create Organisation" }));
    expect(await screen.findByText("Name must not be empty.")).toBeInTheDocument();
    expect(screen.getByLabelText("Slug")).toHaveAttribute("aria-invalid", "true");
    await user.type(screen.getByLabelText("Name"), "Team");
    await user.clear(screen.getByLabelText("Slug"));
    await user.type(screen.getByLabelText("Slug"), "Bad Slug");
    await user.click(screen.getByRole("button", { name: "Create Organisation" }));
    expect(await screen.findByText("Slug may contain lowercase letters, numbers, and hyphens between words.")).toBeInTheDocument();
    expect(creationRequests()).toHaveLength(0);
});

test.each([
    [400, "Check the organisation name and slug"],
    [401, "Authentication required"],
    [403, "You do not have permission"],
    [409, "slug is already in use"],
])("HTTP %i creation failures retain the form and selection with a meaningful error", async (status, message) => {
    fetchMock.mockImplementation(async (_url: string, options: RequestInit) => options.method === "POST"
        ? json({ errors: { slug: "Server slug validation failed." } }, status)
        : json([existing]));
    const client = renderApplication("/organisations/new");
    const user = await fillForm();
    await user.click(screen.getByRole("button", { name: "Create Organisation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.getByLabelText("Name")).toHaveValue("New team");
    expect(screen.getByTestId("location")).toHaveTextContent("/organisations/new");
    expect(screen.getByRole("combobox", { name: "Organisation" })).toHaveValue(existing.id);
    expect(client.getQueryData(["organisations"])).toEqual([existing]);
    expect(serviceRequests()).toHaveLength(0);
    expect(creationRequests()).toHaveLength(1);
    if (status === 401) expect(endSession).toHaveBeenCalledTimes(1);
    else expect(endSession).not.toHaveBeenCalled();
    if (status === 400 || status === 409) expect(screen.getByLabelText("Slug")).toHaveAttribute("aria-invalid", "true");
});
