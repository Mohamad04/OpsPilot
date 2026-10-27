import { Link } from "react-router";
import type { Alert } from "../types/Alert";
import type { MonitoredService } from "../types/MonitoredService";
import { CheckTime } from "./ServiceMonitoring";
import "../styles/alerts.css";

export function AlertBadges({ alert }: { alert: Pick<Alert, "status" | "severity"> }) {
    return <span className="alert-badges"><span className={`alert-badge alert-${alert.status.toLowerCase()}`}>{alert.status === "ACTIVE" ? "Active" : "Resolved"}</span>
        <span className={`alert-badge severity-${alert.severity.toLowerCase()}`}>{alert.severity[0] + alert.severity.slice(1).toLowerCase()}</span></span>;
}
export function AlertFeedback({ error, retry, pending = false }: { error: Error; retry: () => void; pending?: boolean }) {
    return <div role="alert"><p>{error.message}</p><button type="button" className="button-secondary" disabled={pending} onClick={retry}>Try again</button></div>;
}
export function AlertList({ alerts, services = [] }: { alerts: Alert[]; services?: MonitoredService[] }) {
    return <table className="alert-table"><caption className="sr-only">Recorded HTTP availability alerts</caption>
        <thead><tr>{["Service", "Lifecycle / severity", "First seen", "Last seen", "Occurrences", "Details"].map((label) => <th scope="col" key={label}>{label}</th>)}</tr></thead>
        <tbody>{alerts.map((alert) => <tr key={alert.id}>
            <td><span className="alert-cell-label" aria-hidden="true">Service</span><Link to={`/services/${encodeURIComponent(alert.monitoredServiceId)}`}>
                {services.find((service) => service.id === alert.monitoredServiceId && service.organisationId === alert.organisationId)?.name ?? alert.monitoredServiceId}</Link>
                <small className="alert-muted">HTTP availability</small></td>
            <td><span className="alert-cell-label" aria-hidden="true">Lifecycle / severity</span><AlertBadges alert={alert} /></td>
            <td><span className="alert-cell-label" aria-hidden="true">First seen</span><CheckTime value={alert.firstSeenAt} /></td>
            <td><span className="alert-cell-label" aria-hidden="true">Last seen</span><CheckTime value={alert.lastSeenAt} /></td>
            <td><span className="alert-cell-label" aria-hidden="true">Occurrences</span>{alert.occurrenceCount.toLocaleString()}</td>
            <td><Link className="alert-details-link" to={`/alerts/${encodeURIComponent(alert.id)}`} aria-label={`View alert ${alert.id}`}>View details</Link></td>
        </tr>)}</tbody>
    </table>;
}
