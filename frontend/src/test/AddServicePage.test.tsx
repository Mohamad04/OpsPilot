import { beforeEach , expect , test , vi } from "vitest";
import userEvent from "@testing-library/user-event";
import {QueryClient, QueryClientProvider} from "@tanstack/react-query";
import {render , screen} from "@testing-library/react";
import {MemoryRouter} from "react-router";
import {createMonitoredService} from "../api/monitoredServiceApi";
import AddServicePage from "../pages/AddServicePage";


vi.mock("../api/monitoredServiceApi",()=> ({
    createMonitoredService: vi.fn(),
}));

vi.mock("../hooks/useOrganisation", () => ({
    useOrganisation: () => ({
        currentOrganisation: {
            id: "organisation-123",
        },
    }),
}));

beforeEach(() => {
    vi.clearAllMocks();
});

test("shows an error for creating an monitored service with invalid health endpoint", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient();


    render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter>
                <AddServicePage/>
            </MemoryRouter>
            </QueryClientProvider>
    )
    const nameInput= screen.getByLabelText("Name");
    const serviceType = screen.getByRole("combobox", { name: "Service type" });
    const environment = screen.getByRole("combobox", { name: "Environment" });
    const baseUrl = screen.getByLabelText("Base URL");
    const healthEndpoint = screen.getByLabelText("Health Endpoint");
    const owner = screen.getByLabelText("Owner");
    const submitButton = screen.getByRole("button", { name: "Add service" });

    await user.type(nameInput,"Payment API");
    await user.selectOptions(serviceType, "HTTP");
    await user.selectOptions(environment, "PRODUCTION");
    await user.type(baseUrl, "http://www.cedraforge.dev");
    await user.type(healthEndpoint, "invalid/health");
    await user.type(owner, "Owner");
    await user.click(submitButton);

    expect(
        await screen.findByText("Health endpoint must start with '/'.")
    ).toBeInTheDocument();
    expect(
        createMonitoredService
    ).not.toHaveBeenCalled();
})