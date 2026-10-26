import { useQueries } from "@tanstack/react-query";
import { latestHealthOptions } from "../hooks/useMonitoring";
import type { MonitoredService } from "../types/MonitoredService";
import "../styles/monitoring.css";

export default function MonitoringOverview({ organisationId, services }: { organisationId: string; services: MonitoredService[] }) {
    const monitored = services.filter((service) => service.enabled && service.serviceType === "HTTP");
    const queries = useQueries({ queries: monitored.map((service) => latestHealthOptions(organisationId, service.id, true, true)) });
    const failed = queries.filter((query) => query.isError);
    const pending = queries.some((query) => query.isPending || !query.data);
    const fetching = queries.some((query) => query.isFetching);
    const counts = { UP: 0, DOWN: 0, UNKNOWN: 0 };
    let paused = services.filter((service) => !service.enabled).length;
    if (!failed.length && !pending) for (const query of queries) {
        if (query.data) {
            if (!query.data.enabled) paused += 1;
            else counts[query.data.status] += 1;
        }
    }
    return <section className="card monitoring-overview" aria-labelledby="monitoring-overview-title" aria-busy={fetching}>
        <div className="monitoring-section-header"><div><h2 id="monitoring-overview-title">HTTP monitoring overview</h2>
            <p className="health-muted">Latest observations for enabled HTTP services in this organisation. Counts are not uptime.</p></div>
            {monitored.length > 0 && <button type="button" className="button-secondary" disabled={fetching}
                onClick={() => { queries.forEach((query) => { void query.refetch(); }); }}>Refresh monitoring</button>}</div>
        {!monitored.length ? <p>No enabled HTTP services to check.</p>
            : failed.length ? <div role="alert"><h3>Monitoring overview unavailable</h3>
                <p>{failed.length} service{failed.length === 1 ? "" : "s"} could not be loaded. Health totals are hidden until every monitored service is available.</p>
                <p>{failed[0].error?.message}</p>
                <button type="button" disabled={fetching} onClick={() => { failed.forEach((query) => { void query.refetch(); }); }}>Retry monitoring</button></div>
                : pending ? <p aria-live="polite">Loading monitoring observations…</p>
                    : <dl className="monitoring-counts">{(["UP", "DOWN", "UNKNOWN"] as const).map((status) => <div key={status} role="group" aria-label={`Latest ${status}`}>
                        <dt>{status}</dt><dd>{counts[status].toLocaleString()}</dd></div>)}</dl>}
        <p className="health-muted">{paused} paused service{paused === 1 ? "" : "s"}. Disabled services are excluded from observed health totals. Other protocols are not monitored.</p>
        <p className="health-muted">Auto-refreshes every 60 seconds while visible. Timestamps on service details show the age of each observation.</p>
    </section>;
}
