import { StrictMode } from "react";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import AppLayout from "../layouts/AppLayout";
import type { OrganisationContextType } from "../context/OrganisationContext";

const fixture = vi.hoisted(() => ({
    logout: vi.fn(),
    select: vi.fn(),
    organisation: {} as OrganisationContextType,
    displayName: "Alex Example",
}));
vi.mock("../auth/auth", () => ({ logout: fixture.logout }));
vi.mock("../auth/useAuth", () => ({ useAuth: () => ({
    user: { displayName: fixture.displayName, email: "alex@example.com" },
}) }));
vi.mock("../hooks/useOrganisation", () => ({ useOrganisation: () => fixture.organisation }));

let media: MediaQueryList;
beforeEach(() => {
    vi.clearAllMocks();
    fixture.displayName = "Alex Example";
    const organisation = { id: "first", name: "First team", slug: "first", status: "ACTIVE" as const, createdAt: "2026-10-23" };
    fixture.organisation = {
        organisations: [organisation, { ...organisation, id: "second", name: "Second team" }],
        currentOrganisation: organisation, setCurrentOrganisationId: fixture.select,
        organisationsPending: false, organisationsError: null,
    };
    media = Object.assign(new EventTarget(), { matches: false, media: "(min-width: 48rem)" }) as MediaQueryList;
    vi.spyOn(window, "matchMedia").mockReturnValue(media);
});
afterEach(() => vi.restoreAllMocks());

function renderLayout(path = "/") {
    return render(<StrictMode><MemoryRouter initialEntries={[path]}>
        <Routes><Route element={<AppLayout />}>
            <Route path="/" element={<h1>Dashboard content</h1>} />
            <Route path="/services" element={<h1>Services content</h1>} />
            <Route path="/organisations/new" element={<h1>Creation form</h1>} />
        </Route></Routes>
    </MemoryRouter></StrictMode>);
}

test("drawer opens, focuses its close button, locks scrolling, and restores focus on close", async () => {
    renderLayout();
    const user = userEvent.setup();
    const trigger = screen.getByRole("button", { name: "Open navigation" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await user.click(trigger);
    const drawer = screen.getByRole("dialog", { name: "Navigation" });
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    const close = within(drawer).getByRole("button", { name: "Close navigation" });
    expect(close).toHaveFocus();
    expect(document.body.style.overflow).toBe("hidden");
    await user.click(close);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(document.body.style.overflow).toBe("");
});

test("Escape and backdrop dismiss the drawer without route changes", async () => {
    renderLayout();
    const user = userEvent.setup();
    const trigger = screen.getByRole("button", { name: "Open navigation" });
    await user.click(trigger);
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await user.click(trigger);
    fireEvent.click(screen.getByRole("dialog"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Dashboard content" })).toBeInTheDocument();
});

test("drawer navigation retains routes and active state, then closes", async () => {
    renderLayout();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = screen.getByRole("dialog");
    expect(within(drawer).getByRole("link", { name: "Dashboard" })).toHaveAttribute("aria-current", "page");
    await user.click(within(drawer).getByRole("link", { name: "Services" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Services content" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Services" })).toHaveAttribute("aria-current", "page");
});

test("moving to the rail breakpoint closes an open drawer and focuses content", async () => {
    renderLayout();
    await userEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    act(() => {
        Object.assign(media, { matches: true });
        media.dispatchEvent(new Event("change"));
    });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveFocus();
    expect(document.body.style.overflow).toBe("");
});

test("desktop collapse control retains a keyboard accessible expand action", async () => {
    const { container } = renderLayout();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Collapse navigation" }));
    expect(container.querySelector(".app-layout")).toHaveAttribute("data-sidebar-collapsed", "true");
    const expand = screen.getByRole("button", { name: "Expand navigation" });
    expect(expand).toHaveAttribute("aria-expanded", "false");
    await user.keyboard("{Enter}");
    expect(screen.getByRole("button", { name: "Collapse navigation" })).toHaveAttribute("aria-expanded", "true");
});

test("account disclosure supports keyboard access, Escape focus return, and real logout", async () => {
    renderLayout();
    const user = userEvent.setup();
    const account = screen.getByRole("button", { name: "Account for Alex Example" });
    account.focus();
    await user.keyboard("{Enter}{Tab}");
    expect(screen.getByRole("button", { name: "Logout" })).toHaveFocus();
    expect(screen.getByRole("group", { name: "Account actions" })).toHaveTextContent("alex@example.com");
    await user.keyboard("{Escape}");
    expect(account).toHaveFocus();
    expect(account).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("button", { name: "Logout" })).not.toBeInTheDocument();
    await user.click(account);
    await user.click(screen.getByRole("button", { name: "Logout" }));
    expect(fixture.logout).toHaveBeenCalledTimes(1);
});

test("account closes on outside pointer and when keyboard focus leaves the disclosure", async () => {
    renderLayout();
    const user = userEvent.setup();
    const account = screen.getByRole("button", { name: "Account for Alex Example" });
    await user.click(account);
    fireEvent.pointerDown(screen.getByRole("heading"));
    expect(account).toHaveAttribute("aria-expanded", "false");
    await user.click(account);
    await user.keyboard("{Tab}{Shift>}{Tab}{Tab}{/Shift}");
    expect(account).toHaveAttribute("aria-expanded", "false");
});

test("organisation switching and creation reuse existing context and route", async () => {
    renderLayout();
    const user = userEvent.setup();
    await user.selectOptions(screen.getByRole("combobox", { name: "Organisation" }), "second");
    expect(fixture.select).toHaveBeenCalledExactlyOnceWith("second");
    await user.click(screen.getByRole("link", { name: "Create Organisation" }));
    expect(screen.getByRole("heading", { name: "Creation form" })).toBeInTheDocument();
});

test("long identity and organisation names remain available in accessible controls", async () => {
    fixture.displayName = "A very long authenticated user name ".repeat(6).trim();
    fixture.organisation.organisations[0].name = "An exceptionally long organisation name ".repeat(8).trim();
    renderLayout();
    const trigger = screen.getByRole("button", { name: `Account for ${fixture.displayName}` });
    await userEvent.click(trigger);
    expect(within(screen.getByRole("group")).getByText(fixture.displayName)).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toHaveAttribute("title", fixture.organisation.organisations[0].name);
});

test.each(["loading", "error", "empty"])("organisation %s state has accessible feedback and keeps creation reachable", async (state) => {
    fixture.organisation.organisations = [];
    fixture.organisation.currentOrganisation = null;
    fixture.organisation.organisationsPending = state === "loading";
    fixture.organisation.organisationsError = state === "error" ? "Permission denied" : null;
    renderLayout();
    if (state === "loading") expect(screen.getByRole("status")).toHaveTextContent("Loading organisations");
    if (state === "error") expect(screen.getByRole("alert")).toHaveTextContent("Permission denied");
    if (state === "empty") expect(screen.getByRole("heading", { name: "Welcome to OpsPilot" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Dashboard content" })).not.toBeInTheDocument();
    await userEvent.click(within(screen.getByRole("banner")).getByRole("link", { name: "Create Organisation" }));
    expect(screen.getByRole("heading", { name: "Creation form" })).toBeInTheDocument();
});
