import { useState } from "react";
import { Link } from "react-router";
import { useIncidentEvents } from "../hooks/useIncidents";
import { statusLabels } from "../types/Incident";
import type { IncidentEvent, IncidentMember } from "../types/Incident";
import { CheckTime } from "./ServiceMonitoring";
import { IncidentError, IncidentPagination, UserReference } from "./IncidentPresentation";

const eventLabels: Record<string, string> = { CREATED: "Incident created", ALERT_LINKED: "Alert attached", ALERT_RESOLVED: "Alert resolved",
    STATUS_CHANGED: "Status changed", ASSIGNMENT_CHANGED: "Assignment changed", COMMENT_ADDED: "Comment added" };
function TimelineEvent({ event, member }: { event: IncidentEvent; member?: IncidentMember | null }) {
    const human = event.actorUserId !== null;
    const comment = event.eventType === "COMMENT_ADDED" && human;
    const legacy = event.eventType === "COMMENT_ADDED" && !human;
    return <li className={`incident-event ${comment ? "incident-event-comment" : "incident-event-system"}`}>
        <div className="incident-event-heading"><h3>{legacy ? "Historical system note" : eventLabels[event.eventType] ?? "Recorded event"}</h3>
            <CheckTime value={event.occurredAt} /></div>
        <p className="incident-muted">{human ? <>By <UserReference id={event.actorUserId} member={member} /></> : "System-generated"}
            {!eventLabels[event.eventType] && <> · <code>{event.eventType}</code></>}</p>
        {event.previousStatus && event.newStatus && <p>{statusLabels[event.previousStatus]} → {statusLabels[event.newStatus]}</p>}
        {event.eventType === "ASSIGNMENT_CHANGED" && (event.previousAssigneeUserId || event.newAssigneeUserId) && <p className="incident-assignment-change">
            <UserReference id={event.previousAssigneeUserId} member={member} /> → <UserReference id={event.newAssigneeUserId} member={member} /></p>}
        <p className="incident-event-text">{event.details}</p>
        {event.sourceAlertId && <Link to={`/alerts/${event.sourceAlertId}`}>View related alert</Link>}
    </li>;
}
export default function IncidentTimeline({ organisationId, incidentId, member }: { organisationId: string; incidentId: string; member?: IncidentMember | null }) {
    const [page, setPage] = useState(0);
    const query = useIncidentEvents(organisationId, incidentId, page);
    return <section className="card incident-section" aria-labelledby="incident-timeline-title" aria-busy={query.isFetching}>
        <header className="incident-section-heading"><div><h2 id="incident-timeline-title">Incident timeline</h2><p className="incident-muted">Chronological audit events, comments and alert evidence.</p></div>
            <button className="button-secondary" type="button" disabled={query.isFetching} onClick={() => { void query.refetch(); }}>Refresh timeline</button></header>
        {query.isError ? <IncidentError error={query.error} retry={() => { void query.refetch(); }} pending={query.isFetching} />
            : !query.data ? <p role="status">Loading timeline…</p> : <>
                {query.data.content.length ? <ol className="incident-timeline">{query.data.content.map(event => <TimelineEvent key={event.id} event={event} member={member} />)}</ol>
                    : <p>No timeline events on this page.</p>}
                <IncidentPagination data={query.data} label="Timeline pages" pending={query.isFetching} onPage={setPage} />
                {query.data.totalPages > 1 && page < query.data.totalPages - 1 && <button type="button" className="button-secondary" disabled={query.isFetching}
                    onClick={() => setPage(query.data!.totalPages - 1)}>View latest events</button>}
                {page > 0 && !query.data.content.length && <button type="button" className="button-secondary" onClick={() => setPage(0)}>Return to first timeline page</button>}
            </>}
    </section>;
}
