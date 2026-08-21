import { beforeEach , expect , test , vi } from "vitest";
import {QueryClient, QueryClientProvider} from "@tanstack/react-query";
import {render, screen, waitFor} from "@testing-library/react";
import {MemoryRouter} from "react-router";
import ServicesPage from "../pages/ServicesPage";
import {getMonitoredServices} from "../api/monitoredServiceApi.ts";
import type {MonitoredService} from "../types/MonitoredService.ts";

vi.mock("../api/monitoredServiceApi",()=> ({
    getMonitoredServices : vi.fn(),
}))

vi.mock("../hooks/useOrganisation", () => ({
    useOrganisation: () => ({
        currentOrganisation: {
            id: "organisation-123",
        },
        organisationsPending: false,
        organisationsError: null,
    }),
}));

beforeEach(() => {
    vi.clearAllMocks()
})

test("tries to retrieve monitored services but returns empty array", async () => {
    const queryClient = new QueryClient();
    vi.mocked(getMonitoredServices).mockResolvedValue([]);

    render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter>
                <ServicesPage />
            </MemoryRouter>
        </QueryClientProvider>
    );

    await waitFor(() =>{
        expect(
            getMonitoredServices
        ).toHaveBeenCalledWith("organisation-123");
    })

    expect(
        await screen.findByText("No monitored services yet.")
    ).toBeInTheDocument();
})

test("Successfully retrives a monitored service", async () => {
    const queryClient = new QueryClient();
    const retrievedMonitoredServices: MonitoredService[] = [{
        id: "monitored-123",
        name: "Payment API",
        serviceType: "HTTP",
        environment: "PRODUCTION",
        organisationId: "organisation-123",
        owner:"Mohamad",
        enabled: true,
        baseUrl: "http://www.cedraforge.dev",
        healthEndpoint:"/health",
    }];
    vi.mocked(getMonitoredServices).mockResolvedValue(retrievedMonitoredServices);

    render(
        <QueryClientProvider client={queryClient} >
            <MemoryRouter>
                <ServicesPage />
            </MemoryRouter>
        </QueryClientProvider>
    )

    await waitFor(() =>{
        expect(
            getMonitoredServices
        ).toHaveBeenCalledWith("organisation-123");
    })

    expect(
        await screen.findByText("Payment API"),
    ).toBeInTheDocument();

    expect(
        await screen.findByText("PRODUCTION"),
    ).toBeInTheDocument();

    expect(
        await screen.findByText("HTTP"),
    ).toBeInTheDocument();

})

test("Api error state", async () => {
    const queryClient = new QueryClient({
        defaultOptions:{
            queries: {
                retry: false,
            }
        }
    });
    vi.mocked(getMonitoredServices).mockRejectedValue(new Error("Could not retrieve monitored services."));

    render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter>
                <ServicesPage />
            </MemoryRouter>
        </QueryClientProvider>
    )
    await waitFor(() =>{
        expect(
            getMonitoredServices
        ).toHaveBeenCalledWith("organisation-123");
    })
    expect(
        await screen.findByText("Could not retrieve monitored services.")
    ).toBeInTheDocument();
})