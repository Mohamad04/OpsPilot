import { beforeEach , expect , test , vi } from "vitest";
import userEvent from "@testing-library/user-event";
import {QueryClient, QueryClientProvider} from "@tanstack/react-query";
import {render , screen , waitFor} from "@testing-library/react";
import {MemoryRouter} from "react-router";
import CreateOrganisationPage from "../pages/CreateOrganisationPage";
import {createOrganisation} from "../api/organisationApi";
import type {Organisation} from "../types/Organisation";
import { ApiError } from "../api/client";

vi.mock("../api/organisationApi",() => ({
    createOrganisation: vi.fn(),
}));

vi.mock("../hooks/useOrganisation", () => ({
    useOrganisation: () => ({
        setCurrentOrganisationId: vi.fn(),
    }),

}));

beforeEach(() => {
    vi.clearAllMocks();
});

test("shows an error for creating organisation with an invalid slug", async ()=> {
    const user = userEvent.setup();
    const queryClient = new QueryClient();

    render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter>
                <CreateOrganisationPage />
            </MemoryRouter>
        </QueryClientProvider>
    );
    const nameInput = screen.getByLabelText("Name");
    const slugInput = screen.getByLabelText("Slug");
    const submitButton = screen.getByRole("button",{
        name: "Create Organisation",
    })

    await user.type(nameInput,"Cedra Organisation");
    await user.clear(slugInput);
    await user.type(slugInput,"Cedra Organisation");
    await user.click(submitButton);

    expect(
        await screen.findByText("Slug may contain lowercase letters, numbers, and hyphens between words.")
    ).toBeInTheDocument();
    expect(
        createOrganisation
    ).not.toHaveBeenCalled();
})

test("Create an organisation when name and slug are valid", async ()=> {
    const user = userEvent.setup();
    const queryClient = new QueryClient();
    const createdOrganisation: Organisation = {
        id: "0fef0bb3-56bd-4909-af0b-b356bd090909",
        name: "Cedra Organisation",
        slug: "cedra-123",
        status: "ACTIVE",
        createdAt:"2026-08-21T12:00:00Z"
    };
    vi.mocked(createOrganisation).mockResolvedValue(createdOrganisation);


    render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter>
                <CreateOrganisationPage/>
            </MemoryRouter>
        </QueryClientProvider>
    )
    const nameInput =
        screen.getByLabelText("Name");
    const slugInput =
        screen.getByLabelText("Slug");
    const submitButton = screen.getByRole("button",{
        name: "Create Organisation",
    });
    await user.type(nameInput,"Cedra Organisation");
    await user.clear(slugInput);
    await user.type(slugInput, "cedra-123");
    expect(nameInput).toHaveValue("Cedra Organisation");
    await user.click(submitButton);
    const request = {
        name: "Cedra Organisation",
        slug: "cedra-123",
    }
    await waitFor(()=>{
        expect(
            createOrganisation
        ).toHaveBeenCalledWith(request);
    })

})

function renderForm() {
    render(
        <QueryClientProvider client={new QueryClient()}>
            <MemoryRouter><CreateOrganisationPage /></MemoryRouter>
        </QueryClientProvider>
    );
    return userEvent.setup();
}

test("typing and clearing either field leaves the other field unchanged", async () => {
    const user = renderForm();
    const name = screen.getByLabelText("Name");
    const slug = screen.getByLabelText("Slug");
    await user.type(name, "Initial team");
    expect(slug).toHaveValue("");
    await user.type(slug, "custom-team");
    expect(name).toHaveValue("Initial team");
    expect(slug).toHaveValue("custom-team");
    await user.type(slug, "-updated");
    expect(name).toHaveValue("Initial team");
    expect(slug).toHaveValue("custom-team-updated");
    await user.clear(name);
    expect(slug).toHaveValue("custom-team-updated");
    await user.type(name, "Another team");
    expect(slug).toHaveValue("custom-team-updated");
    await user.clear(slug);
    expect(name).toHaveValue("Another team");
});

test("editing Slug cannot fill or clear a required Name validation error", async () => {
    const user = renderForm();
    await user.click(screen.getByRole("button", { name: "Create Organisation" }));
    expect(await screen.findByText("Name must not be empty.")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Slug"), "new-team");
    expect(screen.getByLabelText("Name")).toHaveValue("");
    expect(screen.getByLabelText("Name")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText("Name must not be empty.")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Name"), "My organisation");
    await waitFor(() => expect(screen.getByLabelText("Name")).toHaveAttribute("aria-invalid", "false"));
    expect(screen.getByLabelText("Slug")).toHaveValue("new-team");
    expect(screen.queryByText("Name must not be empty.")).not.toBeInTheDocument();
});

test("slug guidance appears only on focus and untouched fields have no errors", async () => {
    const user = renderForm();
    const slug = screen.getByLabelText("Slug");
    expect(screen.queryByText("2–100 lowercase letters, numbers or hyphens.")).not.toBeInTheDocument();
    expect(slug).not.toHaveAttribute("aria-describedby");
    expect(slug).toHaveAttribute("aria-invalid", "false");
    await user.click(slug);
    expect(slug).toHaveAccessibleDescription("2–100 lowercase letters, numbers or hyphens.");
    await user.type(slug, "Bad Slug");
    expect(screen.queryByText("Slug may contain lowercase letters, numbers, and hyphens between words.")).not.toBeInTheDocument();
    await user.tab();
    expect(await screen.findByText("Slug may contain lowercase letters, numbers, and hyphens between words.")).toBeInTheDocument();
    expect(slug).toHaveAttribute("aria-invalid", "true");
    expect(slug).toHaveAccessibleDescription("Slug may contain lowercase letters, numbers, and hyphens between words.");
    expect(screen.queryByText("2–100 lowercase letters, numbers or hyphens.")).not.toBeInTheDocument();
});

test("errors persist while correcting an invalid slug, then valid blur hides all text feedback", async () => {
    const user = renderForm();
    const slug = screen.getByLabelText("Slug");
    await user.type(slug, "Bad Slug");
    await user.tab();
    expect(await screen.findByText("Slug may contain lowercase letters, numbers, and hyphens between words.")).toBeInTheDocument();
    await user.click(slug);
    await user.clear(slug);
    await user.type(slug, "a");
    expect(await screen.findByText("Slug must not be less than 2 characters.")).toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "Slug format is valid" })).not.toBeInTheDocument();
    await user.type(slug, "cme");
    await waitFor(() => expect(slug).toHaveAttribute("aria-invalid", "false"));
    expect(screen.getByRole("img", { name: "Slug format is valid" })).toBeInTheDocument();
    expect(slug).toHaveAccessibleDescription("2–100 lowercase letters, numbers or hyphens.");
    await user.tab();
    expect(slug).not.toHaveAttribute("aria-describedby");
    expect(screen.queryByText("2–100 lowercase letters, numbers or hyphens.")).not.toBeInTheDocument();
    expect(screen.queryByText("Slug must not be less than 2 characters.")).not.toBeInTheDocument();
});

test("invalid submit reveals required slug validation without requiring prior focus", async () => {
    const user = renderForm();
    await user.click(screen.getByRole("button", { name: "Create Organisation" }));
    expect(await screen.findByText("Slug must not be less than 2 characters.")).toBeInTheDocument();
    expect(screen.getByLabelText("Slug")).toHaveAttribute("aria-invalid", "true");
    expect(createOrganisation).not.toHaveBeenCalled();
});

test("entering Slug first does not populate Name or couple subsequent edits", async () => {
    const user = renderForm();
    const name = screen.getByLabelText("Name");
    const slug = screen.getByLabelText("Slug");
    await user.type(slug, "custom-team");
    expect(name).toHaveValue("");
    await user.type(name, "Completely Different Name");
    expect(slug).toHaveValue("custom-team");
    await user.type(slug, "-updated");
    expect(name).toHaveValue("Completely Different Name");
});

test("a cleared Slug stays empty when Name is edited", async () => {
    const user = renderForm();
    await user.type(screen.getByLabelText("Name"), "Initial team");
    const slug = screen.getByLabelText("Slug");
    await user.type(slug, "custom-team");
    await user.clear(slug);
    await user.type(screen.getByLabelText("Name"), " renamed");
    expect(slug).toHaveValue("");
});

test("Slug length validation remains independent of Name", async () => {
    const user = renderForm();
    await user.type(screen.getByLabelText("Name"), "My team");
    await user.type(screen.getByLabelText("Slug"), "a".repeat(101));
    await user.tab();
    expect(await screen.findByText("Slug must not exceed 100 characters.")).toBeInTheDocument();
    expect(screen.getByLabelText("Name")).toHaveValue("My team");
});

test("duplicate-slug feedback survives focus and blur, then clears when the value changes", async () => {
    const user = renderForm();
    const message = "This organisation slug is already in use. Choose another slug.";
    vi.mocked(createOrganisation).mockRejectedValue(new ApiError(409, message, { slug: message }));
    await user.type(screen.getByLabelText("Name"), "Existing team");
    await user.type(screen.getByLabelText("Slug"), "existing-team");
    await user.click(screen.getByRole("button", { name: "Create Organisation" }));
    const slug = screen.getByLabelText("Slug");
    await waitFor(() => expect(slug).toHaveAccessibleDescription(message));
    await user.click(slug);
    await user.tab();
    expect(slug).toHaveAttribute("aria-invalid", "true");
    expect(slug).toHaveAccessibleDescription(message);
    expect(screen.queryByRole("img", { name: "Slug format is valid" })).not.toBeInTheDocument();
    await user.type(slug, "-unique");
    await waitFor(() => expect(slug).toHaveAttribute("aria-invalid", "false"));
    expect(screen.queryByText(message)).not.toBeInTheDocument();
    await user.tab();
    expect(slug).not.toHaveAttribute("aria-describedby");
});
