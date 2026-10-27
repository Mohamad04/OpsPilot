import { Link } from "react-router";
import { useAlertSummary } from "../hooks/useAlerts";
import { AlertFeedback } from "./AlertPresentation";

export default function AlertOverview({ organisationId }: { organisationId: string }) {
    const query = useAlertSummary(organisationId);
    return <section className="alert-section" aria-labelledby="alert-overview-title" aria-busy={query.isFetching}>
        <div className="alert-heading"><div><h2 id="alert-overview-title">Alert overview</h2><p className="alert-muted">Persisted alert lifecycle, separate from current service health.</p></div>
            <div className="alert-actions"><Link to="/alerts" className="button button-secondary">View alerts</Link>
                <button type="button" className="button-secondary" disabled={query.isFetching} onClick={() => { void query.refetch(); }}>Refresh alert summary</button></div></div>
        {query.isError ? <AlertFeedback error={query.error} retry={() => { void query.refetch(); }} pending={query.isFetching} />
            : !query.data ? <p aria-live="polite">Loading alert summary…</p>
                : <dl className="alert-metrics">{[
                    ["Active alerts", query.data.activeAlerts], ["Resolved alerts", query.data.resolvedAlerts],
                    ["Critical active alerts", query.data.activeBySeverity.CRITICAL],
                ].map(([label, value]) => <div className="card" role="group" aria-label={String(label)} key={label}>
                    <dt>{label}</dt><dd>{Number(value).toLocaleString()}</dd></div>)}</dl>}
    </section>;
}
