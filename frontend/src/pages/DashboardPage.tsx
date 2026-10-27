import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { getMonitoredServices } from "../api/monitoredServiceApi";
import { useOrganisation } from "../hooks/useOrganisation";
import { environmentValues } from "../schemas/createMonitoredServiceSchema";
import Icon from "../components/Icon";
import PageState from "../components/PageState";
import MonitoringOverview from "../components/MonitoringOverview";
import AlertOverview from "../components/AlertOverview";
import "../styles/dashboard.css";

const environmentLabels = {
    DEVELOPMENT: "Development",
    TEST: "Test",
    STAGING: "Staging",
    PRODUCTION: "Production",
};

export default function DashboardPage() {
    const { currentOrganisation, organisationsPending, organisationsError } = useOrganisation();
    const organisationId = currentOrganisation?.id;
    const query = useQuery({
        // Share the raw service-array cache with the registry and its invalidations.
        queryKey: ["monitored-services", organisationId],
        queryFn: () => {
            if (!organisationId) throw new Error("Organisation ID is missing.");
            return getMonitoredServices(organisationId);
        },
        enabled: Boolean(organisationId) && !organisationsPending && !organisationsError,
        // Never show a previous tenant's data while the new query is pending.
        placeholderData: undefined,
    });

    if (organisationsPending) return <PageState variant="loading" title="Loading organisations..." />;
    if (organisationsError) return <PageState variant="error" title="Unable to load organisations">{organisationsError}</PageState>;
    if (!organisationId) return (
        <PageState variant="empty" title="Select an organisation" action={<Link to="/organisations/new" className="button">Create Organisation</Link>}>
            Choose an organisation to view its service totals, or create your first organisation.
        </PageState>
    );
    if (query.isPending) return <PageState variant="loading" title="Loading dashboard...">Fetching service configuration for {currentOrganisation.name}.</PageState>;

    const retry = <button type="button" disabled={query.isFetching} onClick={() => { void query.refetch(); }}>
        {query.isFetching ? "Retrying..." : "Try again"}
    </button>;
    if (query.isError || query.data === undefined) return (
        <PageState variant="error" title="Unable to load dashboard" action={retry}>
            {query.error?.message ?? "Service totals are unavailable."} Totals are hidden until service data can be loaded successfully.
        </PageState>
    );

    const services = query.data;
    if (services.some((service) => service.organisationId !== organisationId)) return (
        <PageState variant="error" title="Unable to verify service data" action={retry}>
            The service response does not match the selected organisation. No totals are displayed.
        </PageState>
    );

    let enabled = 0;
    const byEnvironment = { DEVELOPMENT: 0, TEST: 0, STAGING: 0, PRODUCTION: 0 };
    for (const service of services) {
        if (service.enabled) enabled += 1;
        byEnvironment[service.environment] += 1;
    }
    const metrics = [
        { id: "total", label: "Total registered services", value: services.length, description: "Registered in this organisation", tone: "primary" },
        { id: "enabled", label: "Enabled services", value: enabled, description: "Enabled in service configuration", tone: "enabled" },
        { id: "disabled", label: "Disabled services", value: services.length - enabled, description: "Disabled in service configuration", tone: "disabled" },
    ];

    return (
        <div className="dashboard-page dashboard-overview">
            <header className="dashboard-overview-header">
                <div className="dashboard-heading"><p className="dashboard-eyebrow">Organisation overview</p><h1>Dashboard</h1>
                    <p className="dashboard-muted">Service configuration for <strong>{currentOrganisation.name}</strong>.</p>
                </div>
                <div className="dashboard-actions">
                    <Link to="/services" className="button button-secondary">View services</Link>
                    <Link to="/services/new" className="button"><Icon name="plus" />Add service</Link>
                </div>
            </header>

            {query.isFetching && <p role="status" className="dashboard-refreshing">Refreshing service totals for this organisation...</p>}

            <section aria-labelledby="dashboard-totals-title" className="dashboard-totals">
                <div className="dashboard-section-heading"><h2 id="dashboard-totals-title">Service totals</h2>
                    <p className="dashboard-muted">Enabled and disabled are configuration states. Enabled does not mean healthy.</p></div>
                <dl className="dashboard-metrics-grid">{metrics.map((metric) => (
                    <div key={metric.id} className={`card dashboard-metric dashboard-metric-${metric.tone}`} role="group" aria-labelledby={`dashboard-${metric.id}-label`}>
                        <dt id={`dashboard-${metric.id}-label`}>{metric.label}</dt>
                        <dd><span className="dashboard-metric-value">{metric.value.toLocaleString()}</span>
                            <span className="dashboard-metric-description">{metric.description}</span></dd>
                    </div>
                ))}</dl>
            </section>

            <MonitoringOverview key={organisationId} organisationId={organisationId} services={services} />
            <AlertOverview key={`alerts:${organisationId}`} organisationId={organisationId} />

            <div className="dashboard-sections">
                {services.length === 0 ? <section className="card dashboard-empty" aria-labelledby="dashboard-empty-title">
                    <span className="dashboard-empty-icon"><Icon name="services" /></span>
                    <h2 id="dashboard-empty-title">No services registered yet</h2>
                    <p className="dashboard-muted">Add your first service to start organising its ownership, environment and endpoints.</p>
                    <Link to="/services/new" className="button">Register your first service</Link>
                </section> : <section className="card dashboard-environments" aria-labelledby="dashboard-environments-title">
                    <div className="dashboard-section-heading"><h2 id="dashboard-environments-title">Services by environment</h2>
                        <p className="dashboard-muted">Registered services across your environments</p></div>
                    <dl className="dashboard-environment-list">{environmentValues.map((environment) => (
                        <div key={environment} role="group" aria-label={environmentLabels[environment]}>
                            <dt><span className={`dashboard-environment-badge dashboard-environment-${environment.toLowerCase()}`}>{environmentLabels[environment]}</span></dt>
                            <dd>{byEnvironment[environment].toLocaleString()}<span className="sr-only"> services</span></dd>
                        </div>
                    ))}</dl>
                    <Link to="/services" className="dashboard-registry-link">Open service registry<Icon name="chevronRight" /></Link>
                </section>}

                <section className="card dashboard-monitoring" aria-labelledby="dashboard-monitoring-title">
                    <div className="dashboard-section-heading"><h2 id="dashboard-monitoring-title">Monitoring capabilities</h2>
                        <p className="dashboard-muted">Scheduled HTTP checks are available. Other monitoring capabilities are listed below.</p></div>
                    <ul className="dashboard-capabilities">{["Uptime calculations", "Incidents"].map((feature) => (
                        <li key={feature}><span>{feature}</span><span className="dashboard-unavailable">Not implemented</span></li>
                    ))}</ul>
                    <p className="dashboard-monitoring-note">Health observations do not calculate uptime. HTTP alert rules and history are available; incident management and notification delivery are not implemented.</p>
                </section>
            </div>
        </div>
    );
}
