import { useParams } from "react-router";
import {
    useQuery,
    useMutation,
    useQueryClient,
} from "@tanstack/react-query";

import {
    disableMonitoredService,
    enableMonitoredService,
    getMonitoredService,
} from "../api/monitoredServiceApi";

import { useOrganisation } from "../hooks/useOrganisation";

export default function ServiceDetailsPage() {
    const { id } = useParams();
    const queryClient = useQueryClient();

    const {
        currentOrganisation,
        organisationsPending,
        organisationsError,
    } = useOrganisation();

    const organisationId = currentOrganisation?.id;

    const query = useQuery({
        queryKey: [
            "monitored-service",
            organisationId,
            id,
        ],

        queryFn: () => {
            if (!id) {
                throw new Error("Service ID is missing.");
            }

            if (!organisationId) {
                throw new Error("Organisation is missing.");
            }

            return getMonitoredService(
                organisationId,
                id
            );
        },

        enabled: Boolean(id && organisationId),
    });

    const enableMutation = useMutation({
        mutationFn: () => {
            if (!id) {
                throw new Error("Service ID is missing.");
            }

            if (!organisationId) {
                throw new Error("Organisation is missing.");
            }

            return enableMonitoredService(
                organisationId,
                id
            );
        },

        onSuccess: async () => {
            await Promise.all([
                queryClient.invalidateQueries({
                    queryKey: [
                        "monitored-service",
                        organisationId,
                        id,
                    ],
                }),

                queryClient.invalidateQueries({
                    queryKey: [
                        "monitored-services",
                        organisationId,
                    ],
                }),
            ]);
        },
    });

    const disableMutation = useMutation({
        mutationFn: () => {
            if (!id) {
                throw new Error("Service ID is missing.");
            }

            if (!organisationId) {
                throw new Error("Organisation is missing.");
            }

            return disableMonitoredService(
                organisationId,
                id
            );
        },

        onSuccess: async () => {
            await Promise.all([
                queryClient.invalidateQueries({
                    queryKey: [
                        "monitored-service",
                        organisationId,
                        id,
                    ],
                }),

                queryClient.invalidateQueries({
                    queryKey: [
                        "monitored-services",
                        organisationId,
                    ],
                }),
            ]);
        },
    });

    const service = query.data;

    if (!id) {
        return <p>Service ID is missing.</p>;
    }

    return (
        <div className="service-details-page">
            {organisationsPending && (
                <p>Loading organisation...</p>
            )}

            {organisationsError && (
                <p>{organisationsError}</p>
            )}

            {!organisationsPending &&
                !organisationsError &&
                !organisationId && (
                    <p>No organisation found.</p>
                )}

            {organisationId && query.isPending && (
                <p>Loading service...</p>
            )}

            {query.isError && (
                <p>{query.error.message}</p>
            )}

            {query.isSuccess && service && (
                <div className="service-details-card">
                    <h1>Service Details</h1>

                    <dl className="service-details-list">
                        <dt>Service Name</dt>
                        <dd>{service.name}</dd>

                        <dt>Service Type</dt>
                        <dd>{service.serviceType}</dd>

                        <dt>Service Environment</dt>
                        <dd>{service.environment}</dd>

                        <dt>Service Owner</dt>
                        <dd>{service.owner}</dd>

                        <dt>Service Status</dt>
                        <dd>
                            {service.enabled
                                ? "Enabled"
                                : "Disabled"}
                        </dd>

                        <dt>Service Base URL</dt>
                        <dd>{service.baseUrl}</dd>

                        <dt>Service Health Endpoint</dt>
                        <dd>{service.healthEndpoint}</dd>
                    </dl>

                    {service.enabled ? (
                        <>
                            <button
                                onClick={() =>
                                    disableMutation.mutate()
                                }
                                disabled={
                                    disableMutation.isPending
                                }
                            >
                                {disableMutation.isPending
                                    ? "Disabling..."
                                    : "Disable service"}
                            </button>

                            {disableMutation.isError && (
                                <p>
                                    {
                                        disableMutation.error
                                            .message
                                    }
                                </p>
                            )}
                        </>
                    ) : (
                        <>
                            <button
                                onClick={() =>
                                    enableMutation.mutate()
                                }
                                disabled={
                                    enableMutation.isPending
                                }
                            >
                                {enableMutation.isPending
                                    ? "Enabling..."
                                    : "Enable service"}
                            </button>

                            {enableMutation.isError && (
                                <p>
                                    {
                                        enableMutation.error
                                            .message
                                    }
                                </p>
                            )}
                        </>
                    )}
                </div>
            )}
        </div>
    );
}