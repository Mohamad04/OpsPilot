import { Link } from "react-router";
import type { Incident, IncidentMember, IncidentPage } from "../types/Incident";
import { ongoing, statusLabels } from "../types/Incident";
import type { MonitoredService } from "../types/MonitoredService";
import { CheckTime } from "./ServiceMonitoring";
import "../styles/alerts.css";
import "../styles/incidents.css";

export function IncidentBadges({ incident }: { incident: Pick<Incident, "status" | "severity"> }) {
    return <span className="alert-badges"><span className={`alert-badge incident-status-${incident.status.toLowerCase()}`}>{statusLabels[incident.status]}</span>
        <span className={`alert-badge severity-${incident.severity.toLowerCase()}`}>{incident.severity[0] + incident.severity.slice(1).toLowerCase()} severity</span></span>;
}
export function IncidentError({ error, retry, pending }: { error: Error; retry: () => void; pending: boolean }) {
    return <div className="incident-feedback" role="alert"><p>{error.message}</p>
        <button type="button" className="button-secondary" disabled={pending} onClick={retry}>Try again</button></div>;
}
export function UserReference({ id, member }: { id: string | null; member?: IncidentMember | null }) {
    return id === null ? <>Unassigned</> : member?.currentUserId === id ? <>You</> : <code title={id}>{id}</code>;
}
export function IncidentPagination({ data, label, pending, onPage }: {
    data: Pick<IncidentPage, "page" | "totalPages" | "totalElements" | "first" | "last">;
    label: string; pending: boolean; onPage: (page: number) => void;
}) {
    return <nav className="incident-pagination" aria-label={label}>
        <p>{data.totalElements.toLocaleString()} {label === "Timeline pages" ? "events" : "incidents"}
            {data.totalPages > 0 && <> · Page {data.page + 1} of {data.totalPages}</>}</p>
        <div className="incident-actions"><button type="button" className="button-secondary" disabled={pending || data.first} onClick={() => onPage(data.page - 1)}>Previous</button>
            <button type="button" className="button-secondary" disabled={pending || data.last} onClick={() => onPage(data.page + 1)}>Next</button></div>
    </nav>;
}
export function IncidentList({ incidents, services, member }: { incidents: Incident[]; services: MonitoredService[]; member?: IncidentMember | null }) {
    return <table className="incident-table"><caption className="sr-only">Incidents in the selected organisation</caption>
        <thead><tr>{["Incident", "Status / severity", "Affected service", "Assignee", "Created / resolved"].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
        <tbody>{incidents.map(incident => <tr key={incident.id}>
            <td><Link className="incident-title-link" to={`/incidents/${incident.id}`}>{incident.title}</Link><small><code>INC-{incident.id.slice(0, 8)}</code> · {ongoing(incident.status) ? "Ongoing incident" : "Historical incident"}</small></td>
            <td><span className="incident-cell-label" aria-hidden="true">Status / severity</span><IncidentBadges incident={incident} /></td>
            <td><span className="incident-cell-label" aria-hidden="true">Affected service</span>{incident.monitoredServiceId
                ? <Link to={`/services/${incident.monitoredServiceId}`}>{services.find(service => service.id === incident.monitoredServiceId && service.organisationId === incident.organisationId)?.name ?? incident.monitoredServiceId}</Link>
                : "No service association"}</td>
            <td><span className="incident-cell-label" aria-hidden="true">Assignee</span><UserReference id={incident.assignedUserId} member={member} /></td>
            <td><span className="incident-cell-label" aria-hidden="true">Created / resolved</span><CheckTime value={incident.createdAt} />
                <small>{incident.resolvedAt ? <>Resolved <CheckTime value={incident.resolvedAt} /></> : "No resolution recorded"}</small></td>
        </tr>)}</tbody>
    </table>;
}
