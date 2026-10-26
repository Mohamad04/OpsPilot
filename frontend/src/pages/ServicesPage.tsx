import { useMemo, useState } from "react";
import { Link, useLocation } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { getMonitoredServices } from "../api/monitoredServiceApi";
import { ApiError } from "../api/client";
import { useOrganisation } from "../hooks/useOrganisation";
import { environmentValues, serviceTypes } from "../schemas/createMonitoredServiceSchema";
import type { MonitoredService } from "../types/MonitoredService";
import Icon from "../components/Icon";
import PageState from "../components/PageState";
import { ServiceHealthCell } from "../components/ServiceMonitoring";
import "../styles/services.css";

const pageSize = 25;
const environmentLabels = { DEVELOPMENT: "Development", TEST: "Test", STAGING: "Staging", PRODUCTION: "Production" };

export default function ServicesPage() {
    const location = useLocation();
    const { currentOrganisation, organisationsPending, organisationsError } = useOrganisation();
    const organisationId = currentOrganisation?.id;
    const query = useQuery({
        queryKey: ["monitored-services", organisationId],
        queryFn: () => {
            if (!organisationId) throw new Error("Organisation ID is missing.");
            return getMonitoredServices(organisationId);
        },
        enabled: Boolean(organisationId),
        placeholderData: undefined,
    });

    if (organisationsPending) return <PageState variant="loading" title="Loading organisations..." />;
    if (organisationsError) return <PageState variant="error" title="Unable to load organisations">{organisationsError}</PageState>;
    if (!organisationId) return <PageState variant="empty" title="No organisation found.">Select an organisation to view its services.</PageState>;

    // A revoked membership must not leave previously cached tenant records visible.
    const accessDenied = query.error instanceof ApiError && [401, 403, 404].includes(query.error.status);
    if (query.data?.some((service) => service.organisationId !== organisationId)) return (
        <PageState variant="error" title="Unable to verify service data">The service response does not match the selected organisation.</PageState>
    );

    return (
        <div className="services-page service-registry-page">
            {location.state?.createdOrganisationId === organisationId && (
                <p role="status">Organisation "{currentOrganisation.name}" created successfully.</p>
            )}
            <header className="services-header registry-page-header">
                <div><p className="registry-eyebrow">Service registry</p><h1>Services</h1>
                    <p className="registry-muted">Manage the services registered in {currentOrganisation.name || "your organisation"}.</p></div>
                <Link to="/services/new" className="button add-service"><Icon name="plus" />Add service</Link>
            </header>
            {query.isPending ? <PageState variant="loading" title="Loading services..." />
                : query.data === undefined || accessDenied ? <PageState variant="error" title="Unable to load services" action={
                    <button type="button" disabled={query.isFetching} onClick={() => { void query.refetch(); }}>Try again</button>
                }>{query.error?.message ?? "Services are unavailable."}</PageState>
                : <>
                    {query.isError && <div role="alert" className="registry-refresh-error"><p>Unable to refresh services. {query.error.message}</p>
                        <button type="button" className="button-secondary" disabled={query.isFetching} onClick={() => { void query.refetch(); }}>Retry refresh</button></div>}
                    <ServiceRegistry key={organisationId} organisationId={organisationId} services={query.data} refreshing={query.isFetching} />
                </>}
        </div>
    );
}

function ServiceRegistry({ organisationId, services, refreshing }: { organisationId: string; services: MonitoredService[]; refreshing: boolean }) {
    const [search, setSearch] = useState("");
    const [environment, setEnvironment] = useState("");
    const [serviceType, setServiceType] = useState("");
    const [status, setStatus] = useState("");
    const [page, setPage] = useState(1);
    const filtered = useMemo(() => {
        const term = search.trim().toLowerCase();
        return services.filter((service) => (
            (!term || [service.name, service.owner, service.baseUrl, service.serviceType, service.environment].some((value) => value.toLowerCase().includes(term)))
            && (!environment || service.environment === environment)
            && (!serviceType || service.serviceType === serviceType)
            && (!status || service.enabled === (status === "enabled"))
        ));
    }, [services, search, environment, serviceType, status]);
    const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));
    const currentPage = Math.min(page, pageCount);
    const start = (currentPage - 1) * pageSize;
    const visible = filtered.slice(start, start + pageSize);
    const hasFilters = Boolean(search || environment || serviceType || status);

    function clearFilters() {
        setSearch(""); setEnvironment(""); setServiceType(""); setStatus(""); setPage(1);
    }

    if (services.length === 0) return (
        <section className="card registry-empty" aria-labelledby="registry-empty-title">
            <span className="registry-empty-icon"><Icon name="services" /></span>
            <h2 id="registry-empty-title">No monitored services yet.</h2>
            <p className="registry-muted">Register your first service to keep its ownership and connection details in one place.</p>
            <Link to="/services/new" className="button"><Icon name="plus" />Add service</Link>
        </section>
    );

    return (
        <section className="card service-registry" aria-label="Service registry">
            <div className="registry-toolbar" role="search" aria-label="Find services">
                <div className="registry-control registry-search"><label htmlFor="registry-search">Search services</label>
                    <input id="registry-search" type="search" value={search} placeholder="Search name, owner or URL" aria-describedby="registry-search-help"
                        onChange={(event) => { setSearch(event.target.value); setPage(1); }} />
                    <span id="registry-search-help" className="sr-only">Search by name, owner, base URL, service type or environment.</span>
                </div>
                <div className="registry-control"><label htmlFor="registry-environment">Environment</label>
                    <select id="registry-environment" value={environment} onChange={(event) => { setEnvironment(event.target.value); setPage(1); }}>
                        <option value="">All environments</option>{environmentValues.map((value) => <option key={value} value={value}>{environmentLabels[value]}</option>)}
                    </select>
                </div>
                <div className="registry-control"><label htmlFor="registry-type">Service type</label>
                    <select id="registry-type" value={serviceType} onChange={(event) => { setServiceType(event.target.value); setPage(1); }}>
                        <option value="">All types</option>{serviceTypes.map((value) => <option key={value} value={value}>{value}</option>)}
                    </select>
                </div>
                <div className="registry-control"><label htmlFor="registry-status">Status</label>
                    <select id="registry-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(1); }}>
                        <option value="">All states</option><option value="enabled">Enabled</option><option value="disabled">Disabled</option>
                    </select>
                </div>
            </div>
            <div className="registry-result-bar">
                <p className="registry-results" role="status" aria-atomic="true">
                    {filtered.length ? `Showing ${start + 1}–${Math.min(start + pageSize, filtered.length)} of ${filtered.length} services` : "0 services found"}
                    {hasFilters && ` (${services.length} total)`}
                </p>
                {hasFilters && <button type="button" className="button-secondary" onClick={clearFilters}>Clear filters</button>}
                {refreshing && <span className="registry-muted">Refreshing services...</span>}
            </div>
            {filtered.length === 0 ? <div className="registry-no-results"><h2>No services match your search</h2>
                <p className="registry-muted">Try a different search or clear the filters to see all services.</p></div>
                : <table className="registry-table" role="table">
                    <caption className="sr-only">Registered services. Enabled and Disabled describe configuration, not service health.</caption>
                    <thead role="rowgroup"><tr role="row">
                        <th scope="col" role="columnheader">Service</th><th scope="col" role="columnheader">Type</th>
                        <th scope="col" role="columnheader">Environment</th><th scope="col" role="columnheader">Owner</th><th scope="col" role="columnheader">Configuration</th>
                    </tr></thead>
                    <tbody role="rowgroup">{visible.map((service) => <tr key={service.id} role="row">
                        <td role="cell" className="registry-service-cell"><Link to={`/services/${service.id}`}>{service.name}</Link><span className="registry-service-url">{service.baseUrl}</span>
                            <ServiceHealthCell organisationId={organisationId} service={service} /></td>
                        <td role="cell"><span className="registry-cell-label" aria-hidden="true">Type</span><span className="registry-badge">{service.serviceType}</span></td>
                        <td role="cell"><span className="registry-cell-label" aria-hidden="true">Environment</span><span className={`registry-badge registry-environment-${service.environment.toLowerCase()}`}>{environmentLabels[service.environment]}</span></td>
                        <td role="cell"><span className="registry-cell-label" aria-hidden="true">Owner</span>{service.owner || "Not specified"}</td>
                        <td role="cell"><span className="registry-cell-label" aria-hidden="true">Status</span><span className={`status-indicator ${service.enabled ? "status-enabled" : "status-disabled"}`}>{service.enabled ? "Enabled" : "Disabled"}</span></td>
                    </tr>)}</tbody>
                </table>}
            <footer className="registry-footer">
                <p className="registry-muted">HTTP health shows the latest recorded check. Enabled and disabled describe configuration, independently of observed health.</p>
                {pageCount > 1 && <nav className="registry-pagination" aria-label="Services pagination">
                    <button type="button" className="button-secondary" disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)} aria-label="Previous page"><Icon name="chevronLeft" /><span>Previous</span></button>
                    <span className="registry-page-number">Page {currentPage} of {pageCount}</span>
                    <button type="button" className="button-secondary" disabled={currentPage === pageCount} onClick={() => setPage(currentPage + 1)} aria-label="Next page"><span>Next</span><Icon name="chevronRight" /></button>
                </nav>}
            </footer>
        </section>
    );
}
