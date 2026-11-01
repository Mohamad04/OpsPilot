import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { assignmentInputSchema, incidentTransitions, ongoing, statusLabels } from "../types/Incident";
import type { Incident, IncidentMember, IncidentStatus } from "../types/Incident";
import type { useIncidentWrites } from "../hooks/useIncidents";

type Writes = ReturnType<typeof useIncidentWrites>;
type Action = { version: number } & ({ kind: "status"; status: IncidentStatus } | { kind: "assignment"; userId: string | null });
export type IncidentFeedback = { kind: "success" | "error"; message: string };
export function IncidentConfirmation({ title, description, pending, disabled, confirm, cancel }: {
    title: string; description: string; pending: boolean; disabled: boolean; confirm: () => void; cancel: () => void;
}) {
    const dialog = useRef<HTMLDialogElement>(null);
    // Capture before the dialog's autofocus moves focus away from its trigger.
    const [trigger] = useState(() => document.activeElement instanceof HTMLElement ? document.activeElement : null);
    useEffect(() => {
        const element = dialog.current;
        element?.showModal();
        return () => { element?.close(); trigger?.focus(); };
    }, [trigger]);
    return <dialog ref={dialog} className="incident-dialog" aria-labelledby="incident-confirm-title" aria-describedby="incident-confirm-description"
        onCancel={event => { event.preventDefault(); if (!pending) cancel(); }}
        onKeyDown={event => { if (event.key === "Escape") { event.preventDefault(); if (!pending) cancel(); } }}>
        <form className="incident-section" onSubmit={event => { event.preventDefault(); if (!disabled && !pending) confirm(); }}>
            <h2 id="incident-confirm-title">{title}</h2><p id="incident-confirm-description">{description}</p>
            {disabled && <p role="alert">The incident is refreshing or has changed. Cancel and review the latest details before continuing.</p>}
            <div className="incident-actions"><button autoFocus type="button" className="button-secondary" disabled={pending} onClick={cancel}>Cancel</button>
                <button type="submit" disabled={pending || disabled}>{pending ? "Saving…" : "Confirm change"}</button></div>
        </form>
    </dialog>;
}
export default function IncidentActions({ incident, member, writes, busy, denied, onDenied, onFeedback }: {
    incident: Incident; member: IncidentMember | null; writes: Writes; busy: boolean; denied: boolean;
    onDenied: () => void; onFeedback: (feedback: IncidentFeedback) => void;
}) {
    const [action, setAction] = useState<Action | null>(null);
    const [assignee, setAssignee] = useState(""); const [validation, setValidation] = useState("");
    const guard = useRef(false); const mounted = useRef(false);
    useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
    const allowed = member !== null && member.currentUserRole !== "VIEWER" && !denied;
    const admin = member?.currentUserRole === "OWNER" || member?.currentUserRole === "ADMIN";
    const own = member?.currentUserId === incident.assignedUserId;
    const pending = writes.status.isPending || writes.assignment.isPending;
    const requestAssignment = (id: string | null) => setAction({ kind: "assignment", userId: id, version: incident.version });
    async function confirm() {
        if (!action || !allowed || guard.current || busy || action.version !== incident.version) return;
        guard.current = true;
        try {
            if (action.kind === "status") await writes.status.mutateAsync({ status: action.status, expectedVersion: action.version });
            else await writes.assignment.mutateAsync({ assigneeUserId: action.userId, expectedVersion: action.version });
            if (mounted.current) { setAction(null); setAssignee(""); onFeedback({ kind: "success", message: action.kind === "status" ? "Incident status updated." : "Incident assignment updated." }); }
        } catch (error) {
            if (mounted.current) {
                setAction(null); onFeedback({ kind: "error", message: error instanceof Error ? error.message : "The change could not be saved." });
                if (error instanceof ApiError && error.status === 403) onDenied();
            }
        } finally { guard.current = false; }
    }
    const description = !action ? "" : action.kind === "assignment" ? action.userId === null
        ? "Remove the current assignee? This will be recorded in the incident timeline."
        : "Assign this incident to the selected user? The server verifies that the user is an eligible member of this organisation."
        : action.status === "RESOLVED" || action.status === "CLOSED"
            ? `Mark this incident ${statusLabels[action.status].toLowerCase()}? Active linked alerts prevent resolution or closure. This change will be audited.`
            : !ongoing(incident.status) ? "Reopen this incident for investigation? Another ongoing incident for the same service will prevent reopening."
                : `Move this incident to ${statusLabels[action.status].toLowerCase()}? This change will be recorded with your identity.`;
    return <>
        <section className="card incident-section" aria-labelledby="incident-lifecycle-title"><h2 id="incident-lifecycle-title">Lifecycle</h2>
            {!allowed ? <p className="incident-muted">{member ? "Read-only access. Incident changes require an authorised operational membership."
                : "Your organisation permissions are unavailable. Changes are disabled until they can be verified."}</p>
                : <><p className="incident-muted">Choose the next step. The server validates the transition and current version.</p>
                    <div className="incident-actions">{incidentTransitions[incident.status].map(status => <button type="button" className="button-secondary" key={status} disabled={busy}
                        onClick={() => setAction({ kind: "status", status, version: incident.version })}>{!ongoing(incident.status) && status === "INVESTIGATING" ? "Reopen investigation" : statusLabels[status]}</button>)}</div></>}
        </section>
        <section className="card incident-section" aria-labelledby="incident-assignment-title"><h2 id="incident-assignment-title">Manage assignment</h2>
            {!ongoing(incident.status) ? <p className="incident-muted">Assignment changes are available on ongoing incidents. Reopening uses the lifecycle rules above.</p>
                : !allowed ? <p className="incident-muted">Assignment is read-only for your current access.</p> : <>
                    {(!incident.assignedUserId || admin) && incident.assignedUserId !== member?.currentUserId && <button className="button-secondary" type="button" disabled={busy}
                        onClick={() => requestAssignment(member!.currentUserId)}>Assign to me</button>}
                    {incident.assignedUserId && (admin || own) && <button className="button-secondary" type="button" disabled={busy} onClick={() => requestAssignment(null)}>{admin ? "Unassign" : "Release my assignment"}</button>}
                    {!admin && incident.assignedUserId && !own && <p className="incident-muted">Only OWNER or ADMIN can reassign an incident assigned to another member.</p>}
                    {admin && <form className="standard-form incident-inline-form" noValidate aria-label="Assign incident" onSubmit={event => {
                        event.preventDefault(); const parsed = assignmentInputSchema.safeParse({ assigneeUserId: assignee.trim(), expectedVersion: incident.version });
                        if (!parsed.success) { setValidation("Enter a valid local OpsPilot user UUID."); return; }
                        setValidation(""); requestAssignment(parsed.data.assigneeUserId);
                    }}><div className="form-field"><label htmlFor="incident-assignee">Local user ID</label><input id="incident-assignee" value={assignee} disabled={busy} autoComplete="off"
                        onChange={event => { setAssignee(event.target.value); setValidation(""); }} aria-invalid={Boolean(validation)} aria-describedby={validation ? "incident-assignee-error" : "incident-assignee-hint"} />
                        <p className="incident-muted" id="incident-assignee-hint">Use a known local member UUID; this form does not yet include a directory picker. The server validates membership and eligibility.</p>
                        {validation && <p role="alert" id="incident-assignee-error">{validation}</p>}</div>
                        <button type="submit" disabled={busy || !assignee.trim()}>Assign member</button></form>}
                </>}
        </section>
        {action && <IncidentConfirmation title={action.kind === "status" ? "Change incident status?" : "Change incident assignment?"}
            description={description} pending={pending} disabled={busy && !pending || incident.version !== action.version || !allowed}
            cancel={() => { if (!pending) setAction(null); }} confirm={() => { void confirm(); }} />}
    </>;
}
