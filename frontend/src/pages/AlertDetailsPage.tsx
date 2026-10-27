import { Link, useParams } from "react-router";
import { useOrganisation } from "../hooks/useOrganisation";
import { useAlert, useAlertServices } from "../hooks/useAlerts";
import { AlertBadges, AlertFeedback } from "../components/AlertPresentation";
import { CheckTime } from "../components/ServiceMonitoring";
import PageState from "../components/PageState";

export default function AlertDetailsPage() {
    const { alertId } = useParams();
    const { currentOrganisation, organisationsPending, organisationsError } = useOrganisation();
    if (organisationsPending) return <PageState variant="loading" title="Loading organisations..." />;
    if (organisationsError) return <PageState variant="error" title="Unable to load organisations">{organisationsError}</PageState>;
    if (!currentOrganisation) return <PageState variant="empty" title="Select an organisation">Choose an organisation to view its alerts.</PageState>;
    if (!alertId) return <PageState variant="error" title="Alert ID is missing" />;
    return <Details key={`${currentOrganisation.id}:${alertId}`} organisationId={currentOrganisation.id} name={currentOrganisation.name} alertId={alertId} />;
}
function Details({ organisationId, name, alertId }: { organisationId: string; name: string; alertId: string }) {
    const query = useAlert(organisationId, alertId);
    const services = useAlertServices(organisationId);
    const alert = query.data;
    return <div className="alerts-page"><Link to="/alerts" className="service-back-link">Back to alerts</Link>
        {query.isError ? <AlertFeedback error={query.error} retry={() => { void query.refetch(); }} pending={query.isFetching} />
            : !alert ? <p aria-live="polite">Loading alert details…</p> : <>
                <header className="card alert-detail-header"><p className="alert-eyebrow">{name}</p><h1>HTTP availability alert</h1><AlertBadges alert={alert} />
                    <p className="alert-muted">Alert lifecycle records an outage sequence; it does not describe live service health.</p>
                    <button type="button" className="button-secondary" disabled={query.isFetching} onClick={() => { void query.refetch(); }}>Refresh alert</button></header>
                <div className="alert-detail-grid">
                    <section className="card alert-section" aria-labelledby="alert-facts-title"><h2 id="alert-facts-title">Alert details</h2><dl className="alert-facts">
                        <div><dt>Alert ID</dt><dd><code>{alert.id}</code></dd></div>
                        <div><dt>Organisation ID</dt><dd><code>{alert.organisationId}</code></dd></div>
                        <div><dt>Service</dt><dd><Link to={`/services/${encodeURIComponent(alert.monitoredServiceId)}`}>{services.data?.find((service) => service.id === alert.monitoredServiceId)?.name ?? alert.monitoredServiceId}</Link></dd></div>
                        <div><dt>Category</dt><dd>HTTP availability</dd></div><div><dt>Occurrences</dt><dd>{alert.occurrenceCount.toLocaleString()}</dd></div>
                        <div><dt>First seen</dt><dd><CheckTime value={alert.firstSeenAt} /></dd></div><div><dt>Last seen</dt><dd><CheckTime value={alert.lastSeenAt} /></dd></div>
                        <div><dt>Resolved at</dt><dd>{alert.resolvedAt ? <CheckTime value={alert.resolvedAt} /> : "Not resolved"}</dd></div>
                    </dl></section>
                    <section className="card alert-section" aria-labelledby="alert-evidence-title"><h2 id="alert-evidence-title">Rule snapshot and evidence</h2>
                        <p className="alert-muted">Thresholds and severity were recorded when this alert opened. They may differ from the current rule.</p>
                        <dl className="alert-facts">
                            <div><dt>Opening failure threshold</dt><dd>{alert.failureThreshold}</dd></div><div><dt>Opening recovery threshold</dt><dd>{alert.recoveryThreshold}</dd></div>
                            <div><dt>Recovery threshold used at resolution</dt><dd>{alert.resolvedAfterRecoveries ?? "Not resolved"}</dd></div>
                            <div><dt>Triggering HTTP status</dt><dd>{alert.triggeringHttpStatusCode ?? "No HTTP response"}</dd></div>
                            <div><dt>Triggering response time</dt><dd>{alert.triggeringResponseTimeMs.toLocaleString()} ms</dd></div>
                            {([ ["First failure evidence", alert.firstFailureHealthCheckResultId], ["Triggering evidence", alert.triggeringHealthCheckResultId],
                                ["Last failure evidence", alert.lastFailureHealthCheckResultId], ["Resolution evidence", alert.resolutionHealthCheckResultId] ] as const).map(([label, value]) =>
                                <div key={label}><dt>{label}</dt><dd>{value ? <code>{value}</code> : "Not available"}</dd></div>)}
                        </dl><p className="alert-muted">Evidence identifiers reference persisted health checks. Incident timelines and root-cause analysis are not implemented.</p>
                    </section>
                </div>
            </>}
    </div>;
}
