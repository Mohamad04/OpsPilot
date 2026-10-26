import { ApiError } from "../api/client";
import type { HealthResult, HealthStatus, LatestHealth } from "../api/healthApi";
import type { MonitoredService } from "../types/MonitoredService";
import { useHealthHistory, useLatestHealth } from "../hooks/useMonitoring";
import "../styles/monitoring.css";

export function HealthBadge({ status, paused = false }: { status: HealthStatus; paused?: boolean }) {
    return <span className={`health-badge health-${paused ? "paused" : status.toLowerCase()}`}>{paused ? "Paused" : status}</span>;
}

export function CheckTime({ value }: { value: string }) {
    return <time dateTime={value} title={new Date(value).toISOString()}>{new Date(value).toLocaleString()}</time>;
}

function CheckFacts({ result }: { result: HealthResult }) {
    return <dl className="health-facts">
        <div><dt>HTTP status</dt><dd>{result.httpStatusCode ?? "No HTTP response"}</dd></div>
        <div><dt>Response time</dt><dd>{result.responseTimeMs.toLocaleString()} ms</dd></div>
        <div><dt>Last check</dt><dd><CheckTime value={result.completedAt} /></dd></div>
        {result.failureDetail && <div className="health-failure"><dt>Failure details</dt><dd>{result.failureDetail}</dd></div>}
    </dl>;
}

function CurrentObservation({ latest, enabled }: { latest: LatestHealth; enabled: boolean }) {
    const paused = !enabled || !latest.enabled;
    return <>
        <div className="health-heading"><HealthBadge status={latest.status} paused={paused} />
            <span>{paused ? "Monitoring is disabled. Results below are historical." : "Latest recorded observation"}</span></div>
        {latest.latestResult ? <>
            {paused && <p className="health-muted">Last observed health: {latest.status} (historical).</p>}
            <CheckFacts result={latest.latestResult} />
        </> : <p className="health-muted">No health check has been recorded yet.</p>}
    </>;
}

export function ServiceHealthCell({ organisationId, service }: { organisationId: string; service: MonitoredService }) {
    const supported = service.serviceType === "HTTP";
    const query = useLatestHealth(organisationId, service.id, supported, supported && service.enabled);
    if (!supported) return <div className="monitoring-compact"><HealthBadge status="UNKNOWN" paused={!service.enabled} /><span>HTTP monitoring only</span></div>;
    return <div className="monitoring-compact" aria-label={`Monitoring for ${service.name}`} aria-busy={query.isFetching}>
        {query.isError ? <><span className="health-error">Health unavailable</span><span>{query.error.message}</span>
            <button type="button" className="button-secondary" aria-label={`Retry health for ${service.name}`} disabled={query.isFetching}
                onClick={() => { void query.refetch(); }}>Retry</button></>
            : query.isPending || !query.data ? <span aria-live="polite">Loading health…</span>
                : <><HealthBadge status={query.data.status} paused={!service.enabled || !query.data.enabled} />
                    {query.data.latestResult ? <>
                        <span>{!service.enabled || !query.data.enabled ? `Historical ${query.data.status} · ` : ""}
                            {query.data.latestResult.httpStatusCode === null ? "No HTTP response" : `HTTP ${query.data.latestResult.httpStatusCode}`} · {query.data.latestResult.responseTimeMs.toLocaleString()} ms</span>
                        <CheckTime value={query.data.latestResult.completedAt} />
                    </> : <span>No checks recorded</span>}</>}
    </div>;
}

export default function ServiceMonitoring({ organisationId, service }: { organisationId: string; service: MonitoredService }) {
    const supported = service.serviceType === "HTTP";
    const latest = useLatestHealth(organisationId, service.id, supported, supported && service.enabled);
    const history = useHealthHistory(organisationId, service.id, supported, supported && service.enabled);
    const accessDenied = [latest.error, history.error].some((error) => error instanceof ApiError && [401, 403, 404].includes(error.status));
    return <section className="service-monitoring" aria-label="Service monitoring">
        <div className="monitoring-section-header"><div><h2>HTTP monitoring</h2>
            <p className="health-muted">Latest recorded checks, separate from configuration. {supported && service.enabled ? "Refreshes every 60 seconds while visible." : "Automatic refresh is paused."}</p></div>
            {supported && <button type="button" className="button-secondary" disabled={latest.isFetching || history.isFetching}
                onClick={() => { void latest.refetch(); void history.refetch(); }}>Refresh monitoring</button>}</div>
        {!supported ? <p>UNKNOWN — only HTTP services are monitored. No checks are scheduled for {service.serviceType} services.</p>
            : accessDenied ? <div role="alert" className="health-error-panel"><h3>Monitoring unavailable</h3><p>Access to health data was denied or the service is no longer available. Cached observations are hidden.</p></div>
                : <div className="monitoring-detail-grid">
                    <section className="card health-latest" aria-labelledby="latest-health-title" aria-busy={latest.isFetching}>
                        <h3 id="latest-health-title">Latest health check</h3>
                        {latest.isError ? <div role="alert"><p>{latest.error.message}</p><button type="button" disabled={latest.isFetching} onClick={() => { void latest.refetch(); }}>Retry latest check</button></div>
                            : latest.isPending || !latest.data ? <p aria-live="polite">Loading latest check…</p>
                                : <CurrentObservation latest={latest.data} enabled={service.enabled} />}
                    </section>
                    <section className="card health-history" aria-labelledby="health-history-title" aria-busy={history.isFetching}>
                        <h3 id="health-history-title">Recent health checks</h3><p className="health-muted">Up to 20 recorded checks, newest first. These are past observations.</p>
                        {history.isError ? <div role="alert"><p>{history.error.message}</p><button type="button" disabled={history.isFetching} onClick={() => { void history.refetch(); }}>Retry history</button></div>
                            : history.isPending || !history.data ? <p aria-live="polite">Loading health history…</p>
                                : history.data.length === 0 ? <p>No health-check history yet.</p>
                                    : <div className="health-history-scroll" role="region" aria-label="Scrollable health-check history" tabIndex={0}>
                                        <ol>{history.data.map((result) => <li key={result.id}>
                                            <div className="health-heading"><HealthBadge status={result.status} /><CheckTime value={result.completedAt} /></div>
                                            <p>{result.httpStatusCode === null ? "No HTTP response" : `HTTP ${result.httpStatusCode}`} · {result.responseTimeMs.toLocaleString()} ms</p>
                                            {result.failureDetail && <p className="health-error">{result.failureDetail}</p>}
                                        </li>)}</ol>
                                    </div>}
                    </section>
                </div>}
    </section>;
}
