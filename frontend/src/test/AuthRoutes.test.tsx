import { StrictMode } from "react";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { beforeEach, expect, test, vi } from "vitest";
import App from "../App";
import AuthenticationBoundary from "../auth/AuthenticationBoundary";
import type { AuthState } from "../auth/auth";

const fixture = vi.hoisted(() => ({
    state: { status: "authenticated", user: { subject: "user", displayName: "Alex Example" }, message: null } as AuthState,
    listeners: new Set<() => void>(),
    logout: vi.fn(),
}));

vi.mock("../auth/auth", () => ({
    getAuthState: () => fixture.state,
    subscribeToAuth: (listener: () => void) => {
        fixture.listeners.add(listener);
        return () => fixture.listeners.delete(listener);
    },
    logout: fixture.logout,
}));
vi.mock("../hooks/useOrganisation", () => ({
    useOrganisation: () => ({ organisations: [{ id: "existing", name: "Existing organisation" }], currentOrganisation: null, setCurrentOrganisationId: vi.fn() }),
}));
vi.mock("../pages/DashboardPage", () => ({ default: () => <p>Dashboard route</p> }));
vi.mock("../pages/ServicesPage", () => ({ default: () => <p>Services route</p> }));
vi.mock("../pages/AddServicePage", () => ({ default: () => <p>Add service route</p> }));
vi.mock("../pages/ServiceDetailsPage", () => ({ default: () => <p>Service details route</p> }));
vi.mock("../pages/CreateOrganisationPage", () => ({ default: () => <p>Create organisation route</p> }));

beforeEach(() => {
    fixture.state = { status: "authenticated", user: { subject: "user", displayName: "Alex Example" }, message: null };
    vi.clearAllMocks();
});

function renderRoute(path: string, provisioningError?: string) {
    render(
        <StrictMode>
            <AuthenticationBoundary provisioningError={provisioningError}>
                <MemoryRouter initialEntries={[path]}><App /></MemoryRouter>
            </AuthenticationBoundary>
        </StrictMode>,
    );
}

test.each([
    ["/", "Dashboard route"], ["/services", "Services route"],
    ["/services/new", "Add service route"], ["/services/service-123", "Service details route"],
    ["/organisations/new", "Create organisation route"],
])("authenticated route %s retains its existing page and user header", (path, page) => {
    renderRoute(path);
    expect(screen.getByText(page)).toBeInTheDocument();
    expect(screen.getByText("Alex Example")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Account for Alex Example" })).toBeInTheDocument();
});

test("provisioning failure hides the application and offers explicit retry and logout", () => {
    renderRoute("/services", "Account setup was refused");
    expect(screen.queryByText("Services route")).not.toBeInTheDocument();
    expect(screen.queryByText("Alex Example")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Account setup was refused");
    expect(screen.getByRole("button", { name: "Retry account setup" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Logout" }));
    expect(fixture.logout).toHaveBeenCalledTimes(1);
});

test("the header invokes Keycloak logout", () => {
    renderRoute("/");
    fireEvent.click(screen.getByRole("button", { name: "Account for Alex Example" }));
    fireEvent.click(screen.getByRole("button", { name: "Logout" }));
    expect(fixture.logout).toHaveBeenCalledTimes(1);
});

test("session loss immediately unmounts protected pages and user information", () => {
    renderRoute("/services");
    act(() => {
        fixture.state = { status: "unauthenticated", user: null, message: "Session expired" };
        fixture.listeners.forEach((listener) => listener());
    });
    expect(screen.queryByText("Services route")).not.toBeInTheDocument();
    expect(screen.queryByText("Alex Example")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Session expired");
    expect(screen.getByRole("button", { name: "Sign in with Keycloak" })).toBeInTheDocument();
});
