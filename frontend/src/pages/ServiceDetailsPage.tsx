import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
    disableMonitoredService,
    enableMonitoredService,
    getMonitoredService,
} from "../api/monitoredServiceApi";
import { useOrganisation } from "../hooks/useOrganisation";
import type { MonitoredService } from "../types/MonitoredService";
import Icon from "../components/Icon";
import PageState from "../components/PageState";
import "../styles/service-details.css";

const environmentLabels = {
    DEVELOPMENT: "Development",
    TEST: "Test",
    STAGING: "Staging",
    PRODUCTION: "Production",
};

export default function ServiceDetailsPage() {
    const { id } = useParams();
    const { currentOrganisation, organisationsPending, organisationsError } = useOrganisation();
    const organisationId = currentOrganisation?.id;
    const query = useQuery({
        queryKey: ["monitored-service", organisationId, id],
        queryFn: () => {
            if (!id) throw new Error("Service ID is missing.");
            if (!organisationId) throw new Error("Organisation is missing.");
            return getMonitoredService(organisationId, id);
        },
        enabled: Boolean(id && organisationId),
    });

    if (!id) return <PageState variant="error" title="Service ID is missing." />;
    if (organisationsPending) return <PageState variant="loading" title="Loading organisation..." />;
    if (organisationsError) return <PageState variant="error" title="Unable to load organisation">{organisationsError}</PageState>;
    if (!organisationId) return <PageState variant="empty" title="No organisation found.">Select an organisation to view its services.</PageState>;
    if (query.isPending) return <PageState variant="loading" title="Loading service..." />;
    if (!query.data) return (
        <PageState variant="error" title="Unable to load service" action={
            <button type="button" onClick={() => { void query.refetch(); }} disabled={query.isFetching}>Try again</button>
        }>{query.error?.message ?? "Service details are unavailable."}</PageState>
    );

    return (
        <div className="service-details-page">
            <Link to="/services" className="service-back-link"><Icon name="chevronLeft" />Back to services</Link>
            {query.isError && <div role="alert" className="service-refresh-error">
                <p>Unable to refresh service details. {query.error.message}</p>
                <button type="button" className="button-secondary" disabled={query.isFetching}
                    onClick={() => { void query.refetch(); }}>Retry refresh</button>
            </div>}
            <ServiceDetailsContent
                key={`${organisationId}:${id}`}
                organisationId={organisationId}
                serviceId={id}
                service={query.data}
                refreshing={query.isFetching}
                refreshFailed={query.isError}
            />
        </div>
    );
}

// Reset action/confirmation state when the selected tenant or route changes.
function ServiceDetailsContent({ organisationId, serviceId, service, refreshing, refreshFailed }: {
    organisationId: string;
    serviceId: string;
    service: MonitoredService;
    refreshing: boolean;
    refreshFailed: boolean;
}) {
    const queryClient = useQueryClient();
    const dialogRef = useRef<HTMLDialogElement>(null);
    const actionButtonRef = useRef<HTMLButtonElement>(null);
    const cancelButtonRef = useRef<HTMLButtonElement>(null);
    const [action, setAction] = useState<"enable" | "disable" | null>(null);
    const [feedback, setFeedback] = useState("");

    async function onActionSuccess(state: "enabled" | "disabled") {
        await Promise.all([
            queryClient.invalidateQueries({ queryKey: ["monitored-service", organisationId, serviceId] }),
            queryClient.invalidateQueries({ queryKey: ["monitored-services", organisationId] }),
        ]);
        setFeedback(`Service ${state} successfully.`);
        dialogRef.current?.close();
        setAction(null);
    }

    const enableMutation = useMutation({
        mutationFn: () => enableMonitoredService(organisationId, serviceId),
        onSuccess: () => onActionSuccess("enabled"),
    });
    const disableMutation = useMutation({
        mutationFn: () => disableMonitoredService(organisationId, serviceId),
        onSuccess: () => onActionSuccess("disabled"),
    });
    const pending = enableMutation.isPending || disableMutation.isPending;
    const mutation = action === "enable" ? enableMutation : disableMutation;
    const actionLabel = action === "enable" ? "Enable" : "Disable";
    const stateChanged = action !== null && (action === "enable") === service.enabled;

    useEffect(() => {
        if (!action) return;
        const previousOverflow = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        return () => { document.body.style.overflow = previousOverflow; };
    }, [action]);

    // The trigger is disabled during the mutation and its awaited refetches.
    useEffect(() => {
        if (feedback && !pending) actionButtonRef.current?.focus();
    }, [feedback, pending]);

    function openConfirmation() {
        if (pending || refreshing || refreshFailed) return;
        enableMutation.reset();
        disableMutation.reset();
        setFeedback("");
        setAction(service.enabled ? "disable" : "enable");
        dialogRef.current?.showModal();
        cancelButtonRef.current?.focus();
    }

    function closeConfirmation() {
        if (pending) return;
        dialogRef.current?.close();
        setAction(null);
        actionButtonRef.current?.focus();
    }

    return (
        <>
            <section className="card service-summary" aria-labelledby="service-name">
                <div className="service-summary-content">
                    <p className="service-eyebrow">Service details</p>
                    <h1 id="service-name">{service.name}</h1>
                    <div className="service-badges">
                        <span className={`status-indicator ${service.enabled ? "status-enabled" : "status-disabled"}`}>
                            {service.enabled ? "Enabled" : "Disabled"}
                        </span>
                        <span className="service-badge"><span className="sr-only">Type: </span>{service.serviceType}</span>
                        <span className={`service-badge service-environment-${service.environment.toLowerCase()}`}>
                            <span className="sr-only">Environment: </span>{environmentLabels[service.environment]}
                        </span>
                    </div>
                    <p className="service-muted">Enabled describes the service configuration, not its health.</p>
                </div>
                <div className="service-summary-action">
                    <button ref={actionButtonRef} type="button" className={service.enabled ? "button-secondary" : undefined}
                        onClick={openConfirmation} disabled={pending || refreshing || refreshFailed}
                        aria-haspopup="dialog" aria-controls="service-action-dialog">
                        {pending ? (action === "enable" ? "Enabling..." : "Disabling...")
                            : service.enabled ? "Disable service" : "Enable service"}
                    </button>
                    {refreshing && <span className="service-muted service-refreshing">Refreshing details...</span>}
                </div>
            </section>

            {feedback && <p role="status">{feedback}</p>}

            <div className="service-information-grid">
                <section className="card service-information-card" aria-labelledby="service-overview-title">
                    <div><h2 id="service-overview-title">Service overview</h2><p className="service-muted">Registration and ownership</p></div>
                    <dl className="service-facts">
                        <div><dt>Owner</dt><dd>{service.owner || "Not specified"}</dd></div>
                        <div><dt>Service type</dt><dd>{service.serviceType}</dd></div>
                        <div><dt>Environment</dt><dd>{environmentLabels[service.environment]}</dd></div>
                        <div><dt>Service ID</dt><dd><code>{service.id}</code></dd></div>
                    </dl>
                </section>
                <section className="card service-information-card" aria-labelledby="service-endpoints-title">
                    <div><h2 id="service-endpoints-title">Endpoints</h2><p className="service-muted">Configured connection details</p></div>
                    <dl className="service-facts service-endpoint-facts">
                        <div><dt>Base URL</dt><dd><code>{service.baseUrl}</code></dd></div>
                        <div><dt>Health endpoint</dt><dd><code>{service.healthEndpoint}</code></dd></div>
                    </dl>
                    <p className="service-monitoring-note"><strong>Health monitoring is not yet available.</strong> No health checks or uptime results are reported here.</p>
                </section>
            </div>

            <dialog ref={dialogRef} id="service-action-dialog" className="service-action-dialog"
                aria-labelledby="service-action-title" aria-describedby="service-action-description"
                onClose={() => { setAction(null); if (!pending) actionButtonRef.current?.focus(); }}
                onCancel={(event) => { event.preventDefault(); closeConfirmation(); }}
                onKeyDown={(event) => {
                    if (event.key === "Escape") { event.preventDefault(); closeConfirmation(); }
                }}
                onClick={(event) => { if (event.target === event.currentTarget) closeConfirmation(); }}>
                <form className="service-confirmation" aria-busy={pending} onSubmit={(event) => {
                    event.preventDefault();
                    if (!action || pending || stateChanged || refreshing || refreshFailed) return;
                    mutation.mutate();
                }}>
                    <div className="service-confirmation-heading">
                        <h2 id="service-action-title">{actionLabel} service?</h2>
                        <p id="service-action-description">{actionLabel} <strong>{service.name}</strong>? The service will be marked as {action === "enable" ? "enabled" : "disabled"}.</p>
                    </div>
                    {stateChanged && <p role="alert">The service state has changed. Cancel and review the latest details before continuing.</p>}
                    {refreshFailed && <p role="alert">Service details could not be refreshed. Cancel and reload the page before trying again.</p>}
                    {mutation.isError && <p role="alert">{mutation.error.message}</p>}
                    {pending && <p className="service-action-progress" role="status">{action === "enable" ? "Enabling service..." : "Disabling service..."} Please wait.</p>}
                    <div className="service-confirmation-actions">
                        <button ref={cancelButtonRef} type="button" className="button-secondary" onClick={closeConfirmation} disabled={pending}>Cancel</button>
                        <button type="submit" disabled={pending || stateChanged || refreshing || refreshFailed}>
                            {pending ? "Saving..." : `${actionLabel} service`}
                        </button>
                    </div>
                </form>
            </dialog>
        </>
    );
}
