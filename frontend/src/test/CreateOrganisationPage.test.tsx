import { beforeEach , expect , test , vi } from "vitest";
import userEvent from "@testing-library/user-event";
import {QueryClient, QueryClientProvider} from "@tanstack/react-query";
import {render , screen , waitFor} from "@testing-library/react";
import {MemoryRouter} from "react-router";
import CreateOrganisationPage from "../pages/CreateOrganisationPage";
import {createOrganisation} from "../api/organisationApi";
import type {Organisation} from "../types/Organisation";

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
    await user.type(slugInput, "cedra-123");
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