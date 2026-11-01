import { useState } from "react";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import IncidentActions from "../components/IncidentActions";
import type { IncidentFeedback } from "../components/IncidentActions";
import IncidentComments from "../components/IncidentComments";
import IncidentTimeline from "../components/IncidentTimeline";
import { incidentKeys, useIncident, useIncidentWrites } from "../hooks/useIncidents";
import type { Incident, IncidentEvent, IncidentMember } from "../types/Incident";
import { getAccessToken } from "../auth/auth";
import { ALERT, INCIDENT, KEY, ORG, OTHER_ORG, OTHER_USER, USER, createdAt, eventFixture, incidentFixture, incidentPage, inStatus } from "./incidentFixtures";

vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn() }));
const fetchMock = vi.fn<(url: string, options: RequestInit) => Promise<Response>>();
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const clients: QueryClient[] = [];
let incident: Incident; let events: IncidentEvent[];
let statusError: number | undefined; let statusMessage: string;
let commentHandler: ((options: RequestInit) => Promise<Response>) | undefined;
let requestHandler: ((options: RequestInit, suffix: string) => Promise<Response>) | undefined;
const secondKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
beforeEach(() => {
    vi.clearAllMocks(); vi.stubGlobal("fetch", fetchMock); vi.mocked(getAccessToken).mockResolvedValue("incident-control-token");
    incident = incidentFixture(); events = [eventFixture()]; statusError = undefined; statusMessage = "Incident changed; reload it before retrying";
    commentHandler = undefined; requestHandler = undefined;
    vi.spyOn(crypto, "randomUUID").mockReturnValueOnce(KEY).mockReturnValue(secondKey);
    fetchMock.mockImplementation(async (url, options) => {
        expect(new Headers(options.headers).get("Authorization")).toBe("Bearer incident-control-token");
        const suffix = new URL(url).pathname.replace(`/api/organisations/${ORG}/incidents/${INCIDENT}`, "");
        if (requestHandler && options.method && options.method !== "GET") return requestHandler(options, suffix);
        if (suffix === "/events") return json(incidentPage(events));
        if (suffix === "/status") {
            if (statusError) { incident = { ...incident, version: 5 }; return json({ message: statusMessage }, statusError); }
            const input = JSON.parse(String(options.body));
            incident = { ...incident, status: input.status, version: input.expectedVersion + 1,
                acknowledgedAt: "2026-10-28T12:01:00Z", resolvedAt: ["RESOLVED", "CLOSED"].includes(input.status) ? "2026-10-28T12:02:00Z" : null };
            return json(incident);
        }
        if (suffix === "/assignee") { const input = JSON.parse(String(options.body)); incident = { ...incident, assignedUserId: input.assigneeUserId, version: input.expectedVersion + 1 }; return json(incident); }
        if (suffix === "/comments") {
            if (commentHandler) return commentHandler(options);
            const input = JSON.parse(String(options.body));
            const event = eventFixture({ id: secondKey, eventType: "COMMENT_ADDED", actorUserId: USER, sourceAlertId: null,
                details: input.content, occurredAt: "2026-10-28T12:03:00Z" }); events.push(event); return json(event, 201);
        }
        if (suffix === "") return json(incident);
        throw new Error(`Unexpected fixture request ${url}`);
    });
});
afterEach(() => { clients.forEach(client => client.clear()); clients.length = 0; vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function Controls({ member, composer = true }: { member: IncidentMember | null; composer?: boolean }) {
    const query = useIncident(ORG, INCIDENT); const writes = useIncidentWrites(ORG, INCIDENT);
    const [denied, setDenied] = useState(false); const [feedback, setFeedback] = useState<IncidentFeedback | null>(null);
    if (!query.data) return <p>Loading controls</p>;
    const busy = query.isFetching || writes.status.isPending || writes.assignment.isPending || writes.comment.isPending;
    return <><p data-testid="version">Version {query.data.version}</p>{feedback && <p role={feedback.kind === "error" ? "alert" : "status"}>{feedback.message}</p>}
        <IncidentActions incident={query.data} member={member} writes={writes} busy={busy} denied={denied} onDenied={() => setDenied(true)} onFeedback={setFeedback} />
        {composer && <IncidentComments org={ORG} id={INCIDENT} member={member} writes={writes} busy={busy} denied={denied} onDenied={() => setDenied(true)} onFeedback={setFeedback} />}
        <IncidentTimeline organisationId={ORG} incidentId={INCIDENT} member={member} /></>;
}
function setup(role: IncidentMember["currentUserRole"] | null = "OWNER") {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); clients.push(client);
    const member = role ? { currentUserId: USER, currentUserRole: role } : null;
    const tree = (composer = true) => <QueryClientProvider client={client}><MemoryRouter><Controls member={member} composer={composer} /></MemoryRouter></QueryClientProvider>;
    const view = render(tree()); return { client, ...view, changeComposer: (enabled: boolean) => view.rerender(tree(enabled)) };
}
const calls = (suffix: string) => fetchMock.mock.calls.filter(([url, options]) => new URL(url).pathname.endsWith(suffix) && options.method);
async function confirm(button: string) {
    await userEvent.click(await screen.findByRole("button", { name: button }));
    const dialog = screen.getByRole("dialog"); await userEvent.click(within(dialog).getByRole("button", { name: "Confirm change" }));
}

test.each(["OWNER", "ADMIN", "ENGINEER", "VIEWER", null] as const)("controls follow authoritative %s membership, with unknown access safely read-only", async role => {
    setup(role); await screen.findByTestId("version");
    const write = role !== null && role !== "VIEWER";
    expect(Boolean(screen.queryByRole("button", { name: "Acknowledged" }))).toBe(write);
    expect(Boolean(screen.queryByRole("textbox", { name: "Investigation note" }))).toBe(write);
    expect(Boolean(screen.queryByRole("textbox", { name: "Local user ID" }))).toBe(role === "OWNER" || role === "ADMIN");
    expect(Boolean(screen.queryByRole("button", { name: "Assign to me" }))).toBe(write);
});
test("allowed lifecycle actions use confirmed expectedVersion and invalidate only the original organisation", async () => {
    const { client } = setup();
    client.setQueryData(incidentKeys.list(ORG, {}), incidentPage([incident])); client.setQueryData(incidentKeys.list(OTHER_ORG, {}), incidentPage([]));
    await confirm("Acknowledged"); await screen.findByText("Incident status updated.");
    expect(JSON.parse(String(calls("/status")[0][1].body))).toEqual({ status: "ACKNOWLEDGED", expectedVersion: 0 });
    expect(screen.getByTestId("version")).toHaveTextContent("Version 1");
    expect(client.getQueryState(incidentKeys.list(ORG, {}))?.isInvalidated).toBe(true);
    expect(client.getQueryState(incidentKeys.list(OTHER_ORG, {}))?.isInvalidated).toBe(false);
    expect(screen.getByRole("button", { name: "Investigating" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Acknowledged" })).not.toBeInTheDocument();
});
test.each([
    ["Cannot resolve or close an incident while linked alerts are ACTIVE", "Active linked alerts prevent resolution"],
    ["Another ongoing incident already exists for this monitored service", "Another ongoing incident already exists"],
    ["Incident changed; reload it before retrying", "Details are being refreshed"],
])("409 is meaningful and refreshes version without replaying a stale write: %s", async (message, expected) => {
    incident = inStatus(message.startsWith("Another") ? "RESOLVED" : "INVESTIGATING"); statusError = 409; statusMessage = message;
    setup(); await confirm(message.startsWith("Another") ? "Reopen investigation" : "Resolved");
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(expected));
    expect(calls("/status")).toHaveLength(1); expect(screen.getByTestId("version")).toHaveTextContent("Version 5");
    statusError = undefined;
    await confirm(message.startsWith("Another") ? "Reopen investigation" : "Mitigated");
    await screen.findByText("Incident status updated."); expect(JSON.parse(String(calls("/status")[1][1].body)).expectedVersion).toBe(5);
});
test("confirmation is keyboard accessible, Cancel-focused, and Escape restores the trigger", async () => {
    setup(); const button = await screen.findByRole("button", { name: "Acknowledged" });
    button.focus(); await userEvent.keyboard("{Enter}");
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" })).toHaveFocus();
    await userEvent.keyboard("{Escape}"); expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); expect(button).toHaveFocus(); expect(calls("/status")).toHaveLength(0);
});
test("an incident changing during confirmation cannot submit the captured stale version", async () => {
    const { client } = setup(); await userEvent.click(await screen.findByRole("button", { name: "Acknowledged" }));
    await act(async () => { incident = { ...incident, version: 7 }; client.setQueryData(incidentKeys.detail(ORG, INCIDENT), incident); });
    await waitFor(() => expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm change" })).toBeDisabled()); expect(calls("/status")).toHaveLength(0);
});
test("a backend 403 makes controls read-only and never reports success", async () => {
    statusError = 403; setup(); await confirm("Acknowledged");
    await screen.findByRole("alert"); expect(screen.queryByRole("button", { name: "Acknowledged" })).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Investigation note" })).not.toBeInTheDocument(); expect(calls("/status")).toHaveLength(1);
});
test("administrators validate local UUIDs, assign members and unassign with versions", async () => {
    setup("ADMIN"); await userEvent.type(await screen.findByRole("textbox", { name: "Local user ID" }), "invalid");
    await userEvent.click(screen.getByRole("button", { name: "Assign member" })); expect(await screen.findByRole("alert")).toHaveTextContent("valid local OpsPilot user UUID"); expect(calls("/assignee")).toHaveLength(0);
    await userEvent.clear(screen.getByRole("textbox", { name: "Local user ID" })); await userEvent.type(screen.getByRole("textbox", { name: "Local user ID" }), OTHER_USER);
    await confirm("Assign member"); await screen.findByText("Incident assignment updated.");
    expect(JSON.parse(String(calls("/assignee")[0][1].body))).toEqual({ assigneeUserId: OTHER_USER, expectedVersion: 0 });
    await confirm("Unassign"); await waitFor(() => expect(calls("/assignee")).toHaveLength(2));
    expect(JSON.parse(String(calls("/assignee")[1][1].body))).toEqual({ assigneeUserId: null, expectedVersion: 1 });
});
test("ENGINEER can claim and release their own incident but cannot manage another member", async () => {
    setup("ENGINEER"); await confirm("Assign to me"); await screen.findByText("Incident assignment updated.");
    expect(JSON.parse(String(calls("/assignee")[0][1].body)).assigneeUserId).toBe(USER);
    await confirm("Release my assignment"); await waitFor(() => expect(incident.assignedUserId).toBeNull());
    expect(screen.queryByRole("textbox", { name: "Local user ID" })).not.toBeInTheDocument();
});
test("another member's assignment and completed incidents expose no engineer assignment actions", async () => {
    incident = incidentFixture({ assignedUserId: OTHER_USER }); const view = setup("ENGINEER"); await screen.findByTestId("version");
    expect(screen.queryByRole("button", { name: "Assign to me" })).not.toBeInTheDocument(); expect(screen.queryByRole("button", { name: "Release my assignment" })).not.toBeInTheDocument();
    view.unmount(); incident = inStatus("CLOSED"); setup("OWNER"); await screen.findByTestId("version");
    expect(screen.queryByRole("textbox", { name: "Local user ID" })).not.toBeInTheDocument(); expect(screen.getByRole("button", { name: "Reopen investigation" })).toBeInTheDocument();
});
test("comment validation, length, plaintext rendering and successful timeline invalidation", async () => {
    setup(); const box = await screen.findByRole("textbox", { name: "Investigation note" });
    await userEvent.click(screen.getByRole("button", { name: "Add comment" })); expect(await screen.findByRole("alert")).toHaveTextContent("Write a comment"); expect(calls("/comments")).toHaveLength(0);
    fireEvent.change(box, { target: { value: "x".repeat(4001) } }); fireEvent.submit(screen.getByRole("form", { name: "Add incident comment" }));
    expect(await screen.findByText("Use 4000 characters or fewer.")).toBeInTheDocument(); expect(calls("/comments")).toHaveLength(0);
    fireEvent.change(box, { target: { value: "<script>untrusted text</script>" } }); await userEvent.click(screen.getByRole("button", { name: "Add comment" }));
    await screen.findByText("Comment added to the timeline."); expect(await screen.findByText("<script>untrusted text</script>")).toBeInTheDocument();
    expect(document.querySelector("script")).toBeNull(); expect(box).toHaveValue(""); expect(screen.getByTestId("version")).toHaveTextContent("Version 0");
    expect(new Headers(calls("/comments")[0][1].headers).get("Idempotency-Key")).toBe(KEY);
});
test("retry of an uncertain comment uses the same key across navigation; a new submission gets a new key", async () => {
    let received: IncidentEvent | undefined;
    commentHandler = async () => {
        if (!received) { received = eventFixture({ id: KEY, eventType: "COMMENT_ADDED", actorUserId: USER, sourceAlertId: null, details: "Retry note" }); events.push(received); throw new TypeError("Connection interrupted after receipt"); }
        return json(received, 201);
    };
    const view = setup(); await userEvent.type(await screen.findByRole("textbox", { name: "Investigation note" }), "Retry note");
    await userEvent.click(screen.getByRole("button", { name: "Add comment" })); await screen.findByRole("alert");
    view.changeComposer(false); view.changeComposer(true); expect(await screen.findByRole("textbox", { name: "Investigation note" })).toHaveValue("Retry note");
    await userEvent.click(screen.getByRole("button", { name: /^(Add|Retry) comment$/ })); await screen.findByText("Comment added to the timeline.");
    expect(calls("/comments").map(([, options]) => new Headers(options.headers).get("Idempotency-Key"))).toEqual([KEY, KEY]);
    expect(events.filter(event => event.eventType === "COMMENT_ADDED")).toHaveLength(1);
    commentHandler = undefined;
    await userEvent.type(screen.getByRole("textbox", { name: "Investigation note" }), "Another comment"); await userEvent.click(screen.getByRole("button", { name: "Add comment" }));
    await waitFor(() => expect(calls("/comments")).toHaveLength(3)); expect(new Headers(calls("/comments")[2][1].headers).get("Idempotency-Key")).toBe(secondKey);
});
test("rapid duplicate comment submits send only one request while pending", async () => {
    let finish!: (value: Response) => void; commentHandler = () => new Promise(resolve => { finish = resolve; });
    setup(); fireEvent.change(await screen.findByRole("textbox", { name: "Investigation note" }), { target: { value: "Investigating" } });
    const form = screen.getByRole("form", { name: "Add incident comment" }); fireEvent.submit(form); fireEvent.submit(form);
    await waitFor(() => expect(calls("/comments")).toHaveLength(1)); expect(screen.getByRole("button", { name: "Adding comment…" })).toBeDisabled();
    await act(async () => finish(json(eventFixture({ id: KEY, eventType: "COMMENT_ADDED", actorUserId: USER, sourceAlertId: null, details: "Investigating" }), 201)));
});
test("a late successful comment response cannot remove a newer draft after navigation", async () => {
    let finish!: (value: Response) => void; commentHandler = () => new Promise(resolve => { finish = resolve; });
    const view = setup(); await userEvent.type(await screen.findByRole("textbox", { name: "Investigation note" }), "Original comment");
    await userEvent.click(screen.getByRole("button", { name: "Add comment" })); await waitFor(() => expect(calls("/comments")).toHaveLength(1));
    view.changeComposer(false);
    const key = ["incident-comment-draft", ORG, INCIDENT, USER];
    view.client.setQueryData(key, { content: "New draft after navigation", attempt: null });
    await act(async () => finish(json(eventFixture({ id: KEY, eventType: "COMMENT_ADDED", actorUserId: USER, sourceAlertId: null, details: "Original comment" }), 201)));
    expect(view.client.getQueryData(key)).toEqual({ content: "New draft after navigation", attempt: null });
    view.changeComposer(true); expect(await screen.findByRole("textbox", { name: "Investigation note" })).toHaveValue("New draft after navigation");
});
test.each(["OPEN", "ACKNOWLEDGED", "INVESTIGATING", "MITIGATED", "RESOLVED", "CLOSED"] as const)("%s exposes exactly its supported lifecycle actions", async status => {
    incident = inStatus(status); setup("ENGINEER"); await screen.findByTestId("version");
    const actions: Record<string, string[]> = { OPEN: ["Acknowledged"], ACKNOWLEDGED: ["Investigating", "Resolved"],
        INVESTIGATING: ["Mitigated", "Resolved"], MITIGATED: ["Investigating", "Resolved"], RESOLVED: ["Closed", "Reopen investigation"], CLOSED: ["Reopen investigation"] };
    const section = screen.getByRole("region", { name: "Lifecycle" });
    expect(within(section).getAllByRole("button").map(button => button.textContent)).toEqual(actions[status]);
    // Comments remain available even in RESOLVED/CLOSED, as supported by the backend.
    expect(screen.getByRole("textbox", { name: "Investigation note" })).toBeInTheDocument();
});
test("timeline distinguishes human, system recovery, legacy notes, assignment and future events", async () => {
    events = [eventFixture(), eventFixture({ id: KEY, eventType: "COMMENT_ADDED", actorUserId: USER, sourceAlertId: null, details: "Human investigation" }),
        eventFixture({ id: secondKey, eventType: "COMMENT_ADDED", details: "Legacy automatic recovery" }),
        eventFixture({ id: OTHER_USER, eventType: "ALERT_RESOLVED", details: "Alert recovered" }),
        eventFixture({ id: USER, eventType: "ASSIGNMENT_CHANGED", sourceAlertId: null, actorUserId: USER, previousAssigneeUserId: OTHER_USER, newAssigneeUserId: USER, details: "Assignment changed" }),
        eventFixture({ id: ALERT, eventType: "FUTURE_EVENT", details: "Future compatible context" })];
    setup(); await screen.findByText("Human investigation");
    expect(screen.getByText("Historical system note")).toBeInTheDocument(); expect(screen.getAllByText("System-generated").length).toBeGreaterThan(1);
    expect(screen.getByRole("heading", { name: "Alert resolved" })).toBeInTheDocument(); expect(screen.getByText("FUTURE_EVENT")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "View related alert" })[0]).toHaveAttribute("href", `/alerts/${ALERT}`);
    expect(document.querySelector(`time[datetime="${createdAt}"]`)).toBeInTheDocument();
});
