import { beforeEach , expect , test , vi } from "vitest";
import {QueryClient, QueryClientProvider} from "@tanstack/react-query";
import {render, screen, waitFor} from "@testing-library/react";
import {MemoryRouter, Route, Routes} from "react-router";
import ServiceDetailsPage from "../pages/ServiceDetailsPage";
import {getMonitoredService , enableMonitoredService , disableMonitoredService} from "../api/monitoredServiceApi";
import type {MonitoredService} from "../types/MonitoredService";
import AppLayout from "../layouts/AppLayout.tsx";
import userEvent from "@testing-library/user-event";

vi.mock("../api/monitoredServiceApi", () => ({
    getMonitoredService: vi.fn(),
    enableMonitoredService: vi.fn(),
    disableMonitoredService: vi.fn(),
}));

vi.mock("../hooks/useOrganisation", () => ({
    useOrganisation: () => ({
        currentOrganisation: {
            id: "organisation-123",
        },
        organisationsPending: false,
        organisationsError: null,
    }),
}));

beforeEach(()=>{
    vi.clearAllMocks()
});

test("Api test that returns a service", async ()=> {
    const queryClient = new QueryClient();
    const organisationId = "organisation-123";
    const monitoredServiceId = "monitor-123";
    const retrieveedMonitoredService : MonitoredService = {
        id: "monitor-123",
        organisationId: "organisation-123",
        name: "Payment API",
        serviceType: "HTTP",
        environment: "DEVELOPMENT",
        baseUrl: "http://www.cedraforge.dev",
        healthEndpoint: "/health",
        owner: "Mohamad",
        enabled: true,
    }

    vi.mocked(getMonitoredService).mockResolvedValue(retrieveedMonitoredService);

    render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter initialEntries={["/services/monitor-123"]}>
                <Routes>
                    <Route
                        path="/services/:id"
                        element={<ServiceDetailsPage />}
                    />
                </Routes>
            </MemoryRouter>
        </QueryClientProvider>
    )
    await waitFor(() => {
        expect(
            getMonitoredService
        ).toHaveBeenCalledWith(organisationId ,
            monitoredServiceId);
    })
    expect(
        await screen.findByText("Payment API")
    ).toBeInTheDocument();
    expect(
        await screen.findByText("Disable service")
    ).toBeInTheDocument();
})

test("API test returns a disabled service", async ()=> {
    const queryClient = new QueryClient();
    const organisationId = "organisation-123";
    const monitoredServiceId = "monitor-123";

    const retrievedMonitoredService : MonitoredService = {
        id: "monitor-123",
        organisationId: "organisation-123",
        name: "Payment API",
        serviceType: "HTTP",
        environment: "DEVELOPMENT",
        baseUrl: "http://www.cedraforge.dev",
        healthEndpoint: "/health",
        owner: "Mohamad",
        enabled: false,
    }
    vi.mocked(getMonitoredService).mockResolvedValue(retrievedMonitoredService);
    render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter initialEntries={["/services/monitor-123"]}>
                <Routes>
                    <Route path="services/:id" element={<ServiceDetailsPage />}/>
                </Routes>
            </MemoryRouter>
        </QueryClientProvider>
    )
    await waitFor(() => {
        expect(
            getMonitoredService
        ).toHaveBeenCalledWith(organisationId ,monitoredServiceId);
    })
    await screen.findByRole("button", {
        name: "Enable service",
    })
})

test("API test disabling service", async ()=> {
    const queryClient = new QueryClient();
    const user = userEvent.setup();
    const organisationId = "organisation-123";
    const monitoredServiceId = "monitor-123";
    const enabledService : MonitoredService = {
        id: "monitor-123",
        organisationId: "organisation-123",
        name: "Payment API",
        serviceType: "HTTP",
        environment: "DEVELOPMENT",
        baseUrl: "http://www.cedraforge.dev",
        healthEndpoint: "/health",
        owner: "Mohamad",
        enabled: true,
    }

    const disabledService : MonitoredService = {
        id: "monitor-123",
        organisationId: "organisation-123",
        name: "Payment API",
        serviceType: "HTTP",
        environment: "DEVELOPMENT",
        baseUrl: "http://www.cedraforge.dev",
        healthEndpoint: "/health",
        owner: "Mohamad",
        enabled: false,
    }
    vi.mocked(getMonitoredService).mockResolvedValueOnce(enabledService)
        .mockResolvedValueOnce(disabledService);

    render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter initialEntries={["/services/monitor-123"]}>
                <Routes>
                    <Route path="/services/:id" element={<ServiceDetailsPage />} />
                </Routes>
            </MemoryRouter>
        </QueryClientProvider>
    )

    const disableButton = await screen.findByRole("button", {
        name: "Disable service",
    });

    await user.click(disableButton);
    await waitFor(() => {
        expect(
            getMonitoredService
        ).toHaveBeenCalledWith(organisationId ,monitoredServiceId);
    })


    await waitFor(() => {
        expect(
            disableMonitoredService
        ).toHaveBeenCalledWith(organisationId,
            monitoredServiceId);
    })

    expect(
    await screen.findByText("Disabled")
    ).toBeInTheDocument();
})

test("API test enabling service", async ()=> {
    const queryClient = new QueryClient();
    const user = userEvent.setup();
    const organisationId = "organisation-123";
    const monitoredServiceId = "monitor-123";
    const enabledService : MonitoredService = {
        id: "monitor-123",
        organisationId: "organisation-123",
        name: "Payment API",
        serviceType: "HTTP",
        environment: "DEVELOPMENT",
        baseUrl: "http://www.cedraforge.dev",
        healthEndpoint: "/health",
        owner: "Mohamad",
        enabled: true,
    }

    const disabledService : MonitoredService = {
        id: "monitor-123",
        organisationId: "organisation-123",
        name: "Payment API",
        serviceType: "HTTP",
        environment: "DEVELOPMENT",
        baseUrl: "http://www.cedraforge.dev",
        healthEndpoint: "/health",
        owner: "Mohamad",
        enabled: false,
    }
    vi.mocked(getMonitoredService).mockResolvedValueOnce(disabledService)
        .mockResolvedValueOnce(enabledService);

    render(
        <QueryClientProvider client={queryClient}>
            <MemoryRouter initialEntries={["/services/monitor-123"]} >
                <Routes>
                    <Route
                        path="/services/:id"
                        element={<ServiceDetailsPage />}
                    />
                </Routes>
            </MemoryRouter>
        </QueryClientProvider>
    );

    const enableButton = await screen.findByRole("button", {
        name: "Enable service",
    });
    await user.click(enableButton);

    await waitFor(() => {
        expect(
            getMonitoredService
        ).toHaveBeenCalledWith(organisationId ,monitoredServiceId);
    })

    await waitFor(() => {
        expect(
            enableMonitoredService
        ).toHaveBeenCalledWith(organisationId ,monitoredServiceId);
    })

    expect(
        await screen.findByRole("button", {
            name: "Disable service",
        })
    ).toBeInTheDocument();
})