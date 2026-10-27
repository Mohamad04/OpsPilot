import { useState } from "react";
import { Link, useLocation, useSearchParams } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { useOrganisation } from "../hooks/useOrganisation";
import { alertKeys, useAlerts, useAlertServices } from "../hooks/useAlerts";
import { alertFiltersSchema, alertSeverities, alertStatuses } from "../types/Alert";
import type { AlertFilters } from "../types/Alert";
import PageState from "../components/PageState";
import AlertOverview from "../components/AlertOverview";
import { AlertFeedback, AlertList } from "../components/AlertPresentation";

export default function AlertsPage() {
    const { currentOrganisation, organisationsPending, organisationsError } = useOrganisation();
    const [params] = useSearchParams();
    const location = useLocation();
    if (organisationsPending) return <PageState variant="loading" title="Loading organisations..." />;
    if (organisationsError) return <PageState variant="error" title="Unable to load organisations">{organisationsError}</PageState>;
    if (!currentOrganisation) return <PageState variant="empty" title="Select an organisation" action={<Link to="/organisations/new" className="button">Create Organisation</Link>}>Choose an organisation to view its alerts.</PageState>;
    return <AlertEntry key={location.key} organisationId={currentOrganisation.id} name={currentOrganisation.name} serviceId={params.get("serviceId") ?? ""} />;
}
function AlertEntry({ organisationId, name, serviceId }: { organisationId: string; name: string; serviceId: string }) {
    // Bind an incoming service-history link to its initial tenant, not a later selector value.
    const [entry] = useState({ organisationId, serviceId });
    return <AlertRegistry key={organisationId} organisationId={organisationId} name={name}
        initialServiceId={entry.organisationId === organisationId ? entry.serviceId : ""} />;
}
function AlertRegistry({ organisationId, name, initialServiceId }: { organisationId: string; name: string; initialServiceId: string }) {
    const [filters, setFilters] = useState<AlertFilters>({ page: 0, size: 20, ...(initialServiceId ? { serviceId: initialServiceId } : {}) });
    const [draft, setDraft] = useState({ status: "", severity: "", serviceId: initialServiceId, from: "", to: "" });
    const [error, setError] = useState("");
    const query = useAlerts(organisationId, filters);
    const services = useAlertServices(organisationId);
    const client = useQueryClient();
    function apply(event: React.FormEvent) {
        event.preventDefault();
        const from = draft.from ? new Date(draft.from) : null;
        const to = draft.to ? new Date(draft.to) : null;
        if ((from && !Number.isFinite(from.getTime())) || (to && !Number.isFinite(to.getTime()))) { setError("Choose valid dates."); return; }
        const parsed = alertFiltersSchema.safeParse({ page: 0, size: filters.size,
            status: draft.status || undefined, severity: draft.severity || undefined, serviceId: draft.serviceId || undefined,
            from: from?.toISOString(), to: to?.toISOString() });
        if (!parsed.success) { setError("The From date must be earlier than the To date. Check your filters."); return; }
        setError(""); setFilters(parsed.data);
    }
    return <div className="alerts-page">
        <header className="alert-heading"><div><p className="alert-eyebrow">Organisation alerts</p><h1>Alerts</h1><p className="alert-muted">HTTP availability alerts for <strong>{name}</strong>.</p></div>
            <button type="button" className="button-secondary" disabled={query.isFetching} onClick={() => {
                void query.refetch(); void client.invalidateQueries({ queryKey: alertKeys.summary(organisationId) });
            }}>Refresh alerts</button></header>
        <AlertOverview key={organisationId} organisationId={organisationId} />
        <form className="standard-form alert-filter-form" onSubmit={apply} aria-label="Filter alerts">
            <div className="alert-filter-grid">
                <div className="form-field"><label htmlFor="filter-status">Status</label><select id="filter-status" value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value })}>
                    <option value="">All statuses</option>{alertStatuses.map((status) => <option key={status} value={status}>{status === "ACTIVE" ? "Active" : "Resolved"}</option>)}</select></div>
                <div className="form-field"><label htmlFor="filter-severity">Severity</label><select id="filter-severity" value={draft.severity} onChange={(e) => setDraft({ ...draft, severity: e.target.value })}>
                    <option value="">All severities</option>{alertSeverities.map((severity) => <option key={severity} value={severity}>{severity[0] + severity.slice(1).toLowerCase()}</option>)}</select></div>
                <div className="form-field"><label htmlFor="filter-service">Service</label><select id="filter-service" value={draft.serviceId} onChange={(e) => setDraft({ ...draft, serviceId: e.target.value })}>
                    <option value="">All services</option>{draft.serviceId && !services.data?.some((service) => service.id === draft.serviceId) && <option value={draft.serviceId}>{draft.serviceId}</option>}
                    {services.data?.map((service) => <option key={service.id} value={service.id}>{service.name}</option>)}</select></div>
                <div className="form-field"><label htmlFor="filter-from">From (inclusive)</label><input id="filter-from" type="datetime-local" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} aria-describedby="alert-date-help" aria-invalid={Boolean(error)} /></div>
                <div className="form-field"><label htmlFor="filter-to">To (exclusive)</label><input id="filter-to" type="datetime-local" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} aria-describedby="alert-date-help" aria-invalid={Boolean(error)} /></div>
            </div>
            <p id="alert-date-help" className="alert-muted">Dates use your local time and filter the first occurrence.</p>
            {error && <p role="alert">{error}</p>}
            {services.isError && <p className="alert-muted">Service names are unavailable. Alerts use service IDs. <button type="button" className="button-secondary" onClick={() => { void services.refetch(); }}>Retry service names</button></p>}
            <div className="alert-actions"><button type="submit">Apply filters</button><button type="button" className="button-secondary" onClick={() => {
                setDraft({ status: "", severity: "", serviceId: "", from: "", to: "" }); setFilters({ page: 0, size: 20 }); setError("");
            }}>Clear filters</button></div>
        </form>
        <section className="alert-section" aria-labelledby="alert-results-title" aria-busy={query.isFetching}>
            <h2 id="alert-results-title">Recorded alerts</h2>
            {query.isError ? <AlertFeedback error={query.error} retry={() => { void query.refetch(); }} pending={query.isFetching} />
                : !query.data ? <p aria-live="polite">Loading alerts…</p>
                    : <>{!query.data.content.length ? <div className="card alert-empty"><h3>No alerts found</h3><p>No recorded alerts match these filters. Health checks must meet the rule thresholds before an alert is created.</p></div>
                        : <AlertList alerts={query.data.content} services={services.data} />}
                        <nav className="alert-pagination" aria-label="Alert pagination"><p aria-live="polite">{query.data.totalElements.toLocaleString()} alerts · Page {query.data.page + 1} of {Math.max(1, query.data.totalPages)}</p>
                            <div className="alert-actions"><button type="button" className="button-secondary" disabled={query.data.first || query.isFetching} onClick={() => setFilters({ ...filters, page: (filters.page ?? 0) - 1 })}>Previous</button>
                                <button type="button" className="button-secondary" disabled={query.data.last || query.isFetching} onClick={() => setFilters({ ...filters, page: (filters.page ?? 0) + 1 })}>Next</button></div></nav></>}
        </section>
    </div>;
}
