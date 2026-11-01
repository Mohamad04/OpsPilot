import { useState } from "react";
import { Link, useParams } from "react-router";
import { useOrganisation } from "../hooks/useOrganisation";
import { useAlertServices } from "../hooks/useAlerts";
import { useIncident, useIncidentWrites } from "../hooks/useIncidents";
import { membershipContextSchema } from "../types/Incident";
import type { Organisation } from "../types/Organisation";
import PageState from "../components/PageState";
import { IncidentBadges, IncidentError, UserReference } from "../components/IncidentPresentation";
import IncidentTimeline from "../components/IncidentTimeline";
import IncidentActions from "../components/IncidentActions";
import type { IncidentFeedback } from "../components/IncidentActions";
import IncidentComments from "../components/IncidentComments";
import { CheckTime } from "../components/ServiceMonitoring";

export default function IncidentDetailsPage() {
    const { incidentId } = useParams();
    const { currentOrganisation, organisationsPending, organisationsError } = useOrganisation();
    if (organisationsPending) return <PageState variant="loading" title="Loading organisations..." />;
    if (organisationsError) return <PageState variant="error" title="Unable to load organisations">{organisationsError}</PageState>;
    if (!currentOrganisation) return <PageState variant="empty" title="Select an organisation">Choose an organisation to view its incidents.</PageState>;
    if (!incidentId) return <PageState variant="error" title="Incident ID is missing" />;
    return <Details key={`${currentOrganisation.id}:${incidentId}`} organisation={currentOrganisation} id={incidentId} />;
}
function Details({ organisation, id }: { organisation: Organisation; id: string }) {
    const query = useIncident(organisation.id, id); const services = useAlertServices(organisation.id);
    const writes = useIncidentWrites(organisation.id, id);
    const [feedback, setFeedback] = useState<IncidentFeedback | null>(null); const [denied, setDenied] = useState(false);
    const parsed = membershipContextSchema.safeParse(organisation); const member = parsed.success ? parsed.data : null;
    const incident = query.data;
    const busy = query.isFetching || writes.status.isPending || writes.assignment.isPending || writes.comment.isPending;
    return <div className="incidents-page incident-details"><Link className="service-back-link" to="/incidents">Back to incidents</Link>
        {query.isError ? <IncidentError error={query.error} retry={() => { void query.refetch(); }} pending={query.isFetching} />
            : !incident ? <p role="status">Loading incident details…</p> : <>
                <header className="card incident-summary"><div className="incident-heading"><div><p className="incident-eyebrow">{organisation.name} · INC-{incident.id.slice(0, 8)}</p>
                    <h1>{incident.title}</h1></div><button type="button" className="button-secondary" disabled={busy} onClick={() => { void query.refetch(); }}>Refresh incident</button></div>
                    <IncidentBadges incident={incident} /><p className="incident-description">{incident.description}</p>
                </header>
                {feedback && <div className={`incident-feedback incident-feedback-${feedback.kind}`} role={feedback.kind === "error" ? "alert" : "status"}>{feedback.message}</div>}
                <div className="incident-detail-grid"><div className="incident-main">
                    <IncidentTimeline key={`${organisation.id}:${id}`} organisationId={organisation.id} incidentId={id} member={member} />
                    <IncidentComments key={`${organisation.id}:${id}:${member?.currentUserId ?? "unknown"}`} org={organisation.id} id={id} member={member} denied={denied} busy={busy} writes={writes}
                        onDenied={() => setDenied(true)} onFeedback={setFeedback} />
                </div><aside className="incident-context" aria-label="Incident context and actions">
                    <section className="card incident-section" aria-labelledby="incident-context-title"><h2 id="incident-context-title">Incident context</h2><dl className="incident-facts">
                        <div><dt>Affected service</dt><dd>{incident.monitoredServiceId ? <Link to={`/services/${incident.monitoredServiceId}`}>
                            {services.data?.find(service => service.id === incident.monitoredServiceId)?.name ?? incident.monitoredServiceId}</Link> : "No service association"}</dd></div>
                        <div><dt>Assignee</dt><dd><UserReference id={incident.assignedUserId} member={member} /></dd></div>
                        <div><dt>Created</dt><dd><CheckTime value={incident.createdAt} /></dd></div>
                        <div><dt>Acknowledged</dt><dd>{incident.acknowledgedAt ? <CheckTime value={incident.acknowledgedAt} /> : "Not acknowledged"}</dd></div>
                        <div><dt>Resolved</dt><dd>{incident.resolvedAt ? <CheckTime value={incident.resolvedAt} /> : "No resolution recorded"}</dd></div>
                        <div><dt>Incident ID</dt><dd><code>{incident.id}</code></dd></div><div><dt>Version</dt><dd>{incident.version}</dd></div>
                    </dl>{services.isError && <p className="incident-muted">Service names are unavailable; the service identifier is shown.</p>}</section>
                    <IncidentActions incident={incident} member={member} writes={writes} busy={busy} denied={denied}
                        onDenied={() => setDenied(true)} onFeedback={setFeedback} />
                    <p className="incident-muted">Alert recovery does not automatically resolve an incident. Related alerts are linked from individual timeline events.</p>
                </aside></div>
            </>}
    </div>;
}
