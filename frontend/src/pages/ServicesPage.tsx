import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";

import { getMonitoredServices } from "../api/monitoredServiceApi";
import { useOrganisation } from "../hooks/useOrganisation";

function ServicesPage() {
    const {
        currentOrganisation,
        organisationsPending,
        organisationsError,
    } = useOrganisation();

    const organisationId = currentOrganisation?.id;

    const query = useQuery({
        queryKey: ["monitored-services", organisationId],

        queryFn: () => {
            if (!organisationId) {
                throw new Error("Organisation ID is missing.");
            }

            return getMonitoredServices(organisationId);
        },

        enabled: Boolean(organisationId),
    });

    const services = query.data ?? [];

    return (
        <div className="services-page">
            <div className="services-header">
                <h1>Services</h1>

                <Link to="new" className="add-service">
                    Add service
                </Link>
            </div>

            <div className="services-content">
                {organisationsPending && (
                    <p>Loading organisations...</p>
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
                    <p>Loading services...</p>
                )}

                {query.isError && (
                    <p>{query.error.message}</p>
                )}

                {query.isSuccess && services.length === 0 && (
                    <p>No monitored services yet.</p>
                )}

                {query.isSuccess && services.length > 0 && (
                    <table className="services-table">
                        <thead>
                        <tr>
                            <th>Name</th>
                            <th>Type</th>
                            <th>Environment</th>
                            <th>Owner</th>
                            <th>Status</th>
                        </tr>
                        </thead>

                        <tbody>
                        {services.map((service) => (
                            <tr key={service.id}>
                                <td>
                                    <Link
                                        to={`/services/${service.id}`}
                                    >
                                        {service.name}
                                    </Link>
                                </td>

                                <td>{service.serviceType}</td>
                                <td>{service.environment}</td>
                                <td>{service.owner}</td>

                                <td>
                                    {service.enabled
                                        ? "Enabled"
                                        : "Disabled"}
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}

export default ServicesPage;