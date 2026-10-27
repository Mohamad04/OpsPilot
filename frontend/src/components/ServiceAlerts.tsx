import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/client";
import { useAlertRule, useAlerts, useSaveAlertRule } from "../hooks/useAlerts";
import { alertRuleInputSchema, alertSeverities } from "../types/Alert";
import type { AlertRule, AlertRuleInput } from "../types/Alert";
import type { MonitoredService } from "../types/MonitoredService";
import { AlertFeedback, AlertList } from "./AlertPresentation";

const input = (rule: AlertRule): AlertRuleInput => ({ enabled: rule.enabled, consecutiveFailures: rule.consecutiveFailures,
    consecutiveRecoveries: rule.consecutiveRecoveries, severity: rule.severity });
const same = (a: AlertRuleInput, b: AlertRuleInput) => a.enabled === b.enabled && a.consecutiveFailures === b.consecutiveFailures
    && a.consecutiveRecoveries === b.consecutiveRecoveries && a.severity === b.severity;
type Draft = { values: AlertRuleInput; baseline: AlertRuleInput };

export function AlertConfiguration({ organisationId, service }: { organisationId: string; service: MonitoredService }) {
    const query = useAlertRule(organisationId, service.id);
    return <section className="card alert-section alert-rule-section" aria-labelledby="alert-configuration-title" aria-busy={query.isFetching}>
        <div><h2 id="alert-configuration-title">Alert configuration</h2>
            <p className="alert-muted">Monitoring controls HTTP checks. Alerting controls evaluation of their results; both must be enabled for evaluation.</p></div>
        {!service.enabled && <p>Service monitoring is disabled. Existing alerts remain in history; new alert evaluation is paused.</p>}
        {query.isError ? <AlertFeedback error={query.error} retry={() => { void query.refetch(); }} pending={query.isFetching} />
            : !query.data ? <p aria-live="polite">Loading alert configuration…</p>
                : <RuleEditor key={`${organisationId}:${service.id}`} organisationId={organisationId} serviceId={service.id} rule={query.data} refreshing={query.isFetching} />}
    </section>;
}

function RuleEditor({ organisationId, serviceId, rule, refreshing }: { organisationId: string; serviceId: string; rule: AlertRule; refreshing: boolean }) {
    const client = useQueryClient();
    const draftKey = ["alert-rule-draft", organisationId, serviceId] as const;
    const [draft] = useState(() => client.getQueryData<Draft>(draftKey));
    const [baseline, setBaseline] = useState(draft?.baseline ?? input(rule));
    const { register, handleSubmit, reset, control, formState: { errors, isSubmitting } } = useForm<AlertRuleInput>({
        resolver: zodResolver(alertRuleInputSchema), defaultValues: draft?.values ?? input(rule), mode: "onTouched", reValidateMode: "onChange",
    });
    const [enabled, consecutiveFailures, consecutiveRecoveries, severity] = useWatch({ control,
        name: ["enabled", "consecutiveFailures", "consecutiveRecoveries", "severity"] });
    const values = useMemo(() => ({ enabled, consecutiveFailures, consecutiveRecoveries, severity }),
        [enabled, consecutiveFailures, consecutiveRecoveries, severity]);
    const changed = !same(values, baseline);
    const serverChanged = !same(input(rule), baseline);
    const save = useSaveAlertRule(organisationId, serviceId);
    const [success, setSuccess] = useState(false);
    const [denied, setDenied] = useState(false);
    const submitting = useRef(false);
    const mounted = useRef(false);
    const pending = isSubmitting || save.isPending;
    useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
    useEffect(() => {
        const key = ["alert-rule-draft", organisationId, serviceId];
        if (changed) {
            // Drafts have no active observer: retain them until saved/discarded or this tab closes.
            client.setQueryDefaults(key, { gcTime: Infinity });
            client.setQueryData<Draft>(key, { values, baseline });
        }
        else client.removeQueries({ queryKey: key, exact: true });
    }, [changed, values, baseline, client, organisationId, serviceId]);
    useEffect(() => {
        if (!changed) return;
        const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
        window.addEventListener("beforeunload", warn);
        return () => window.removeEventListener("beforeunload", warn);
    }, [changed]);

    async function submit(data: AlertRuleInput) {
        if (submitting.current || denied || serverChanged || refreshing) return;
        submitting.current = true; setSuccess(false);
        try {
            const saved = await save.mutateAsync(data);
            client.removeQueries({ queryKey: draftKey, exact: true });
            if (mounted.current) { setBaseline(input(saved)); reset(input(saved)); setSuccess(true); }
        } catch (error) {
            if (mounted.current && error instanceof ApiError && error.status === 403) setDenied(true);
        } finally { submitting.current = false; }
    }
    function discard() { reset(input(rule)); setBaseline(input(rule)); setSuccess(false); save.reset(); }

    return <form className="standard-form alert-rule-form" aria-label="Alert configuration" noValidate aria-busy={pending}
        onSubmit={(event) => { void handleSubmit(submit)(event); }}>
        {rule.id === null && <p className="alert-muted">Effective defaults — no rule has been saved yet.</p>}
        <p className="alert-muted" id="alert-permissions">Editing requires OWNER, ADMIN or ENGINEER membership. The server verifies permissions when you save.</p>
        {denied && <p role="alert">This configuration is read-only for your account. Your changes were not saved. Discard them to view the confirmed configuration.</p>}
        <fieldset disabled={pending || denied} aria-describedby="alert-permissions" className="alert-rule-fields">
            <legend className="sr-only">HTTP alert settings</legend>
            <label className="alert-checkbox"><input type="checkbox" {...register("enabled")} />Alerting enabled</label>
            {([ ["consecutiveFailures", "Consecutive failures"], ["consecutiveRecoveries", "Consecutive recoveries"] ] as const).map(([name, label]) => <div className="form-field" key={name}>
                <label htmlFor={`alert-${name}`}>{label} <span className="alert-muted">(1–20, required)</span></label>
                <input type="number" id={`alert-${name}`} min={1} max={20} step={1} required {...register(name, { valueAsNumber: true })}
                    aria-invalid={Boolean(errors[name])} aria-describedby={errors[name] ? `alert-${name}-error` : undefined} />
                {errors[name] && <p id={`alert-${name}-error`} role="alert">Enter a whole number between 1 and 20.</p>}
            </div>)}
            <div className="form-field"><label htmlFor="alert-severity">Severity (required)</label><select id="alert-severity" {...register("severity")} required>
                {alertSeverities.map((severity) => <option key={severity} value={severity}>{severity[0] + severity.slice(1).toLowerCase()}</option>)}
            </select></div>
        </fieldset>
        {changed && <p className="alert-muted">Unsaved changes are kept in memory when navigating within OpsPilot. Save or discard them before closing this tab.</p>}
        {serverChanged && <p role="alert">The server configuration has changed. Discard your draft to review it before saving.</p>}
        {save.isError && <p role="alert">{save.error.message} Your changes were not saved.</p>}
        {success && !changed && <p role="status">Alert configuration saved.</p>}
        <div className="alert-actions"><button type="submit" disabled={pending || denied || refreshing || serverChanged || !changed}>{pending ? "Saving alert configuration…" : "Save alert configuration"}</button>
            {(changed || serverChanged) && <button type="button" className="button-secondary" disabled={pending} onClick={discard}>Discard changes</button>}</div>
    </form>;
}

export function ServiceAlertHistory({ organisationId, service }: { organisationId: string; service: MonitoredService }) {
    const query = useAlerts(organisationId, { page: 0, size: 5 }, service.id);
    return <section className="alert-section" aria-labelledby="service-alert-history-title" aria-busy={query.isFetching}>
        <header className="alert-heading"><div><h2 id="service-alert-history-title">Alert history</h2><p className="alert-muted">Up to five latest HTTP availability alerts, active and resolved.</p></div>
            <div className="alert-actions"><Link to={`/alerts?serviceId=${encodeURIComponent(service.id)}`} className="button button-secondary">View all service alerts</Link>
                <button className="button-secondary" type="button" disabled={query.isFetching} onClick={() => { void query.refetch(); }}>Refresh alerts</button></div></header>
        {query.isError ? <AlertFeedback error={query.error} retry={() => { void query.refetch(); }} pending={query.isFetching} />
            : !query.data ? <p aria-live="polite">Loading alert history…</p>
                : !query.data.content.length ? <p className="card alert-empty">No alerts recorded for this service.</p>
                    : <AlertList alerts={query.data.content} services={[service]} />}
    </section>;
}
