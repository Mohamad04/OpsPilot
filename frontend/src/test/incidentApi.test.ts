import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { getAccessToken, endSession } from "../auth/auth";
import { addIncidentComment, changeIncidentAssignee, changeIncidentStatus, getIncident, getIncidentEvents, getIncidents } from "../api/incidentApi";
import { incidentKeys } from "../hooks/useIncidents";
import { ALERT, INCIDENT, KEY, ORG, OTHER_ORG, SERVICE, USER, eventFixture, incidentFixture, incidentPage, inStatus } from "./incidentFixtures";

vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn() }));
const fetchMock = vi.fn();
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
beforeEach(() => { vi.clearAllMocks(); vi.stubGlobal("fetch", fetchMock); vi.mocked(getAccessToken).mockResolvedValue("incident-test-token"); });
afterEach(() => vi.unstubAllGlobals());

test("incident list uses server pagination, filtering, whitelisted sort, bearer authentication and cancellation", async () => {
    const controller = new AbortController();
    fetchMock.mockResolvedValue(json(incidentPage([], 2, 10, 20)));
    await getIncidents(ORG, { page: 2, size: 10, status: "RESOLVED", severity: "CRITICAL", serviceId: SERVICE, sort: "createdAt,asc" }, controller.signal);
    const [url, options] = fetchMock.mock.calls[0];
    expect(new URL(url).pathname).toBe(`/api/organisations/${ORG}/incidents`);
    expect(Object.fromEntries(new URL(url).searchParams)).toEqual({ page: "2", size: "10", status: "RESOLVED", severity: "CRITICAL", serviceId: SERVICE, sort: "createdAt,asc" });
    expect(new Headers(options.headers).get("Authorization")).toBe("Bearer incident-test-token"); expect(options.signal).toBe(controller.signal);
});
test("list defaults match the backend and never paginate an unbounded response", async () => {
    fetchMock.mockResolvedValue(json(incidentPage([]))); await getIncidents(ORG);
    expect(Object.fromEntries(new URL(fetchMock.mock.calls[0][0]).searchParams)).toEqual({ page: "0", size: "20", sort: "createdAt,desc" });
});
test.each(["OPEN", "ACKNOWLEDGED", "INVESTIGATING", "MITIGATED", "RESOLVED", "CLOSED"] as const)("actual %s incident lifecycle DTO is accepted", async status => {
    fetchMock.mockResolvedValue(json(inStatus(status))); expect(await getIncident(ORG, INCIDENT)).toEqual(inStatus(status));
});
test.each([{ organisationId: OTHER_ORG }, { id: ALERT }, { status: "ACTIVE" }, { version: -1 }, { version: Number.MAX_SAFE_INTEGER + 1 },
    { createdAt: "invalid" }, { acknowledgedAt: "2026-01-01T00:00:00Z" }, { resolvedAt: "2026-10-29T00:00:00Z" }])("invalid or foreign incident response fails closed: %j", async change => {
    fetchMock.mockResolvedValue(json({ ...incidentFixture(), ...change })); await expect(getIncident(ORG, INCIDENT)).rejects.toMatchObject({ status: 502 });
});
test("paginated incident scope, filters, duplicate identities and metadata are checked", async () => {
    for (const body of [incidentPage([incidentFixture({ organisationId: OTHER_ORG })]), incidentPage([incidentFixture(), incidentFixture()]),
        incidentPage([], 1), { ...incidentPage([]), totalPages: 9 }]) {
        fetchMock.mockResolvedValue(json(body)); await expect(getIncidents(ORG)).rejects.toMatchObject({ status: 502 });
    }
    fetchMock.mockResolvedValue(json(incidentPage([incidentFixture()])));
    for (const filters of [{ status: "CLOSED" as const }, { severity: "LOW" as const }, { serviceId: ALERT }]) await expect(getIncidents(ORG, filters)).rejects.toMatchObject({ status: 502 });
});
test.each([{ page: -1 }, { size: 0 }, { size: 101 }, { page: 2147483647, size: 100 }, { serviceId: "not-a-uuid" }])("invalid query never calls fetch: %j", async filters => {
    await expect(getIncidents(ORG, filters)).rejects.toThrow(); expect(fetchMock).not.toHaveBeenCalled();
});
test("event pagination supports human, system, legacy and unknown types without leaking extra fields", async () => {
    const event = eventFixture({ eventType: "FUTURE_EVENT" });
    fetchMock.mockResolvedValue(json(incidentPage([{ ...event, internalDiagnostic: "must not escape" }])));
    expect((await getIncidentEvents(ORG, INCIDENT)).content).toEqual([event]);
    expect(new URL(fetchMock.mock.calls[0][0]).pathname).toBe(`/api/organisations/${ORG}/incidents/${INCIDENT}/events`);
    fetchMock.mockResolvedValue(json(incidentPage([{ ...event, previousAssigneeUserId: undefined, newAssigneeUserId: undefined }])));
    expect((await getIncidentEvents(ORG, INCIDENT)).content[0].previousAssigneeUserId).toBeNull();
});
test("events from another tenant or incident, duplicate events and wrong pages are rejected", async () => {
    for (const body of [incidentPage([eventFixture({ organisationId: OTHER_ORG })]), incidentPage([eventFixture({ incidentId: ALERT })]),
        incidentPage([eventFixture(), eventFixture()]), incidentPage([], 1)]) {
        fetchMock.mockResolvedValue(json(body)); await expect(getIncidentEvents(ORG, INCIDENT)).rejects.toMatchObject({ status: 502 });
    }
});
test("status updates use expectedVersion and validate the confirmed response", async () => {
    const updated = { ...inStatus("ACKNOWLEDGED"), version: 1 }; fetchMock.mockResolvedValue(json(updated));
    expect(await changeIncidentStatus(ORG, INCIDENT, "ACKNOWLEDGED", 0)).toEqual(updated);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ status: "ACKNOWLEDGED", expectedVersion: 0 });
    expect(fetchMock.mock.calls[0][1].method).toBe("PATCH");
    fetchMock.mockResolvedValue(json({ ...updated, version: 0 })); await expect(changeIncidentStatus(ORG, INCIDENT, "ACKNOWLEDGED", 0)).rejects.toMatchObject({ status: 502 });
});
test("assign and unassign accept a confirmed change or matching-version no-op", async () => {
    for (const version of [0, 1]) { fetchMock.mockResolvedValue(json(incidentFixture({ assignedUserId: USER, version })));
        await expect(changeIncidentAssignee(ORG, INCIDENT, USER, 0)).resolves.toMatchObject({ assignedUserId: USER }); }
    fetchMock.mockResolvedValue(json(incidentFixture({ version: 2 }))); await changeIncidentAssignee(ORG, INCIDENT, null, 1);
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({ assigneeUserId: null, expectedVersion: 1 });
    fetchMock.mockResolvedValue(json(incidentFixture({ version: 20 }))); await expect(changeIncidentAssignee(ORG, INCIDENT, null, 1)).rejects.toMatchObject({ status: 502 });
});
test("comments send only plain content and a bounded retry key, then validate trusted author and scope", async () => {
    const comment = eventFixture({ eventType: "COMMENT_ADDED", actorUserId: USER, sourceAlertId: null, details: "<script>text</script>" });
    fetchMock.mockResolvedValue(json(comment, 201)); expect(await addIncidentComment(ORG, INCIDENT, " <script>text</script> ", KEY, USER)).toEqual(comment);
    const options = fetchMock.mock.calls[0][1]; expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ content: "<script>text</script>" }); expect(new Headers(options.headers).get("Idempotency-Key")).toBe(KEY);
    for (const change of [{ actorUserId: null }, { organisationId: OTHER_ORG }, { sourceAlertId: ALERT }, { incidentId: ALERT }, { details: "Different text" }]) {
        fetchMock.mockResolvedValue(json({ ...comment, ...change }, 201)); await expect(addIncidentComment(ORG, INCIDENT, comment.details, KEY, USER)).rejects.toMatchObject({ status: 502 });
    }
});
test("valid upper-case assignee UUIDs match the backend's canonical response", async () => {
    fetchMock.mockResolvedValue(json(incidentFixture({ assignedUserId: KEY, version: 1 })));
    await expect(changeIncidentAssignee(ORG, INCIDENT, KEY.toUpperCase(), 0)).resolves.toMatchObject({ assignedUserId: KEY });
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).assigneeUserId).toBe(KEY);
});
test.each(["", " \n\t", "x".repeat(4001), "NUL\0text"])("invalid comment content makes no request", async content => {
    await expect(addIncidentComment(ORG, INCIDENT, content, KEY, USER)).rejects.toThrow(); expect(fetchMock).not.toHaveBeenCalled();
});
test.each([400, 401, 403, 404, 500])("HTTP %i remains an error without automatic mutation retry", async status => {
    fetchMock.mockResolvedValue(json({}, status)); await expect(getIncident(ORG, INCIDENT)).rejects.toMatchObject({ status });
    expect(fetchMock).toHaveBeenCalledTimes(1); expect(endSession).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
});
test.each([
    ["Cannot resolve or close an incident while linked alerts are ACTIVE", "Active linked alerts prevent"],
    ["Another ongoing incident already exists for this monitored service", "Another ongoing incident already exists"],
    ["SQL secret diagnostics", "The incident or membership changed"],
])("409 has safe, actionable feedback: %s", async (message, expected) => {
    fetchMock.mockResolvedValue(json({ message }, 409)); await expect(changeIncidentStatus(ORG, INCIDENT, "RESOLVED", 0)).rejects.toMatchObject({ status: 409, message: expect.stringContaining(expected) });
    expect(fetchMock).toHaveBeenCalledTimes(1);
});
test("malformed JSON is controlled and every query key includes its tenant and resource", async () => {
    fetchMock.mockResolvedValue(new Response("invalid")); await expect(getIncident(ORG, INCIDENT)).rejects.toMatchObject({ status: 502 });
    expect(incidentKeys.detail(ORG, INCIDENT)).not.toEqual(incidentKeys.detail(OTHER_ORG, INCIDENT));
    expect(incidentKeys.eventPage(ORG, INCIDENT, 0)).not.toEqual(incidentKeys.eventPage(ORG, INCIDENT, 1));
    expect(incidentKeys.list(ORG, { page: 0 })).not.toEqual(incidentKeys.list(ORG, { page: 1 }));
    expect(incidentKeys.events(ORG, INCIDENT)).toContain(INCIDENT); expect(incidentKeys.events(ORG, INCIDENT)).toContain(ORG);
});
