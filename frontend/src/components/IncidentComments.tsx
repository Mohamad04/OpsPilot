import { useEffect, useRef, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { commentInputSchema } from "../types/Incident";
import type { IncidentMember } from "../types/Incident";
import type { useIncidentWrites } from "../hooks/useIncidents";
import { ApiError } from "../api/client";
import type { IncidentFeedback } from "./IncidentActions";

type Draft = { content: string; attempt: { content: string; key: string } | null };
export default function IncidentComments({ org, id, member, denied, busy, writes, onDenied, onFeedback }: {
    org: string; id: string; member: IncidentMember | null; denied: boolean; busy: boolean;
    writes: ReturnType<typeof useIncidentWrites>; onDenied: () => void; onFeedback: (value: IncidentFeedback) => void;
}) {
    const client = useQueryClient(); const draftKey = ["incident-comment-draft", org, id, member?.currentUserId ?? "unknown"] as const;
    const [draft] = useState(() => client.getQueryData<Draft>(draftKey));
    const { register, handleSubmit, control, reset, formState: { errors, isSubmitting } } = useForm<{ content: string }>({
        resolver: zodResolver(commentInputSchema), defaultValues: { content: draft?.content ?? "" }, mode: "onTouched", reValidateMode: "onChange",
    });
    const content = useWatch({ control, name: "content" });
    const [attempt, setAttempt] = useState<Draft["attempt"]>(draft?.attempt ?? null);
    const inFlight = useRef(false); const mounted = useRef(false);
    const allowed = member !== null && member.currentUserRole !== "VIEWER" && !denied;
    useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
    useEffect(() => {
        const key = ["incident-comment-draft", org, id, member?.currentUserId ?? "unknown"];
        if (content) { client.setQueryDefaults(key, { gcTime: Infinity }); client.setQueryData<Draft>(key, { content, attempt }); }
        else client.removeQueries({ queryKey: key, exact: true });
    }, [client, content, attempt, org, id, member?.currentUserId]);
    useEffect(() => {
        if (!content) return;
        const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
        window.addEventListener("beforeunload", warn); return () => window.removeEventListener("beforeunload", warn);
    }, [content]);
    async function submit(values: { content: string }) {
        if (!member || !allowed || busy || inFlight.current) return;
        inFlight.current = true;
        const text = values.content.trim();
        const next = attempt?.content === text ? attempt : { content: text, key: crypto.randomUUID() };
        setAttempt(next);
        // Persist the receipt before sending, including when navigation interrupts this component.
        client.setQueryDefaults(draftKey, { gcTime: Infinity }); client.setQueryData<Draft>(draftKey, { content: values.content, attempt: next });
        try {
            await writes.comment.mutateAsync({ content: text, key: next.key, userId: member.currentUserId });
            // A late response must not erase a newer draft written after navigation.
            const current = client.getQueryData<Draft>(draftKey);
            if (current?.attempt?.key === next.key && current.content.trim() === text) {
                client.removeQueries({ queryKey: draftKey, exact: true });
            }
            if (mounted.current) { reset({ content: "" }); setAttempt(null); onFeedback({ kind: "success", message: "Comment added to the timeline." }); }
        } catch (error) {
            if (mounted.current) { onFeedback({ kind: "error", message: error instanceof Error ? error.message : "Unable to add your comment." });
                if (error instanceof ApiError && error.status === 403) onDenied(); }
        } finally { inFlight.current = false; }
    }
    return <section className="card incident-section" aria-labelledby="incident-comment-title"><h2 id="incident-comment-title">Add a comment</h2>
        {!allowed ? <p className="incident-muted">Comments are read-only for your current access. Existing comments remain in the timeline.</p>
            : <form className="standard-form incident-inline-form" aria-label="Add incident comment" noValidate onSubmit={event => { void handleSubmit(submit)(event); }}>
                <div className="form-field"><label htmlFor="incident-comment">Investigation note</label><textarea id="incident-comment" rows={5} maxLength={4000} {...register("content")}
                    disabled={writes.comment.isPending || isSubmitting} aria-invalid={Boolean(errors.content)}
                    aria-describedby={`incident-comment-help incident-comment-count${errors.content ? " incident-comment-error" : ""}`} />
                    <div className="incident-comment-meta"><p className="incident-muted" id="incident-comment-help">Plain text, up to 4000 characters. Comments cannot be edited or deleted.</p>
                        <p id="incident-comment-count">{content.length} / 4000</p></div>
                    {errors.content && <p role="alert" id="incident-comment-error">{errors.content.message}</p>}</div>
                {content && <p className="incident-muted">Your draft and retry key stay in this tab's memory when navigating. Closing the tab discards them.</p>}
                <div className="incident-actions"><button type="submit" disabled={busy || isSubmitting}>{writes.comment.isPending ? "Adding comment…" : writes.comment.isError && attempt?.content === content.trim() ? "Retry comment" : "Add comment"}</button>
                    {content && <button type="button" className="button-secondary" disabled={busy || isSubmitting} onClick={() => { reset({ content: "" }); setAttempt(null); writes.comment.reset(); }}>Discard draft</button>}</div>
            </form>}
    </section>;
}
