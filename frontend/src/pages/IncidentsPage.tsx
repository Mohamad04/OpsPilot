import { useState } from "react";
import { Link, useLocation, useSearchParams } from "react-router";
import { useOrganisation } from "../hooks/useOrganisation";
import { useAlertServices } from "../hooks/useAlerts";
import { useIncidents } from "../hooks/useIncidents";
import { alertSeverities } from "../types/Alert";
import { incidentFiltersSchema, incidentStatuses, membershipContextSchema, statusLabels } from "../types/Incident";
import type { IncidentFilters } from "../types/Incident";
import type { Organisation } from "../types/Organisation";
import PageState from "../components/PageState";
import { IncidentError, IncidentList, IncidentPagination } from "../components/IncidentPresentation";

export default function IncidentsPage() {
    const { currentOrganisation, organisationsPending, organisationsError } = useOrganisation();
    const [params] = useSearchParams(); const location = useLocation();
    if (organisationsPending) return <PageState variant="loading" title="Loading organisations..." />;
    if (organisationsError) return <PageState variant="error" title="Unable to load organisations">{organisationsError}</PageState>;
    if (!currentOrganisation) return <PageState variant="empty" title="Select an organisation" action={<Link className="button" to="/organisations/new">Create Organisation</Link>}>Choose an organisation to view its incidents.</PageState>;
    return <IncidentEntry key={location.key} organisation={currentOrganisation} initialServiceId={params.get("serviceId") ?? ""} />;
}
function IncidentEntry({ organisation, initialServiceId }: { organisation: Organisation; initialServiceId: string }) {
    const [entry] = useState({ org: organisation.id, serviceId: initialServiceId });
    return <Registry key={organisation.id} organisation={organisation} initialServiceId={entry.org === organisation.id ? entry.serviceId : ""} />;
}
function Registry({ organisation, initialServiceId }: { organisation: Organisation; initialServiceId: string }) {
    const [draft, setDraft] = useState({ status: "", severity: "", serviceId: initialServiceId, sort: "createdAt,desc" });
    const [filters, setFilters] = useState<IncidentFilters>({ page: 0, size: 20, ...(initialServiceId ? { serviceId: initialServiceId } : {}) });
    const [error, setError] = useState("");
    const query = useIncidents(organisation.id, filters); const services = useAlertServices(organisation.id);
    const identity = membershipContextSchema.safeParse(organisation);
    function apply(event: React.FormEvent) {
        event.preventDefault();
        const parsed = incidentFiltersSchema.safeParse({ ...draft, status: draft.status || undefined,
            severity: draft.severity || undefined, serviceId: draft.serviceId || undefined, page: 0, size: filters.size });
        if (!parsed.success) { setError("Choose valid incident filters."); return; }
        setFilters(parsed.data); setError("");
    }
    function clear() { setDraft({ status: "", severity: "", serviceId: "", sort: "createdAt,desc" }); setFilters({ page: 0, size: 20 }); setError(""); }
    return <div className="incidents-page">
        <header className="incident-heading"><div><p className="incident-eyebrow">Incident management</p><h1>Incidents</h1>
            <p className="incident-muted">Investigate and coordinate operational incidents for <strong>{organisation.name}</strong>.</p></div>
            <button type="button" className="button-secondary" disabled={query.isFetching} onClick={() => { void query.refetch(); }}>Refresh incidents</button></header>
        <form className="standard-form incident-filters" aria-label="Incident filters" onSubmit={apply}>
            <div className="incident-filter-grid">
                <div className="form-field"><label htmlFor="incident-status-filter">Status</label><select id="incident-status-filter" value={draft.status} onChange={event => setDraft({ ...draft, status: event.target.value })}>
                    <option value="">All statuses</option>{incidentStatuses.map(value => <option key={value} value={value}>{statusLabels[value]}</option>)}</select></div>
                <div className="form-field"><label htmlFor="incident-severity-filter">Severity</label><select id="incident-severity-filter" value={draft.severity} onChange={event => setDraft({ ...draft, severity: event.target.value })}>
                    <option value="">All severities</option>{alertSeverities.map(value => <option key={value} value={value}>{value[0] + value.slice(1).toLowerCase()}</option>)}</select></div>
                <div className="form-field"><label htmlFor="incident-service-filter">Affected service</label><select id="incident-service-filter" disabled={services.isFetching || services.isError} value={draft.serviceId} onChange={event => setDraft({ ...draft, serviceId: event.target.value })}>
                    <option value="">All services</option>{draft.serviceId && !services.data?.some(service => service.id === draft.serviceId) && <option value={draft.serviceId}>{draft.serviceId}</option>}
                    {services.data?.map(service => <option key={service.id} value={service.id}>{service.name}</option>)}</select></div>
                <div className="form-field"><label htmlFor="incident-sort">Created</label><select id="incident-sort" value={draft.sort} onChange={event => setDraft({ ...draft, sort: event.target.value })}>
                    <option value="createdAt,desc">Newest first</option><option value="createdAt,asc">Oldest first</option></select></div>
            </div>
            {error && <p role="alert">{error}</p>}
            <div className="incident-actions"><button type="submit" disabled={query.isFetching}>Apply filters</button><button type="button" className="button-secondary" onClick={clear}>Clear filters</button></div>
            {services.isError && <p className="incident-muted">Service names could not be loaded. Incident identifiers remain available. <button type="button" className="button-secondary" onClick={() => { void services.refetch(); }}>Retry service names</button></p>}
        </form>
        <section className="incident-section" aria-label="Incident registry" aria-busy={query.isFetching}>
            {query.isError ? <IncidentError error={query.error} retry={() => { void query.refetch(); }} pending={query.isFetching} />
                : !query.data ? <p role="status">Loading incidents…</p> : <>
                    {query.data.content.length ? <IncidentList incidents={query.data.content} services={services.data ?? []} member={identity.success ? identity.data : null} />
                        : <div className="card incident-empty"><h2>{filters.status || filters.severity || filters.serviceId ? "No matching incidents" : "No incidents recorded"}</h2>
                            <p>Incidents are created automatically from qualifying alerts. Try adjusting filters or review the service registry.</p><Link className="button button-secondary" to="/services">View services</Link></div>}
                    <IncidentPagination data={query.data} label="Incident pages" pending={query.isFetching} onPage={page => setFilters({ ...filters, page })} />
                </>}
        </section>
    </div>;
}
