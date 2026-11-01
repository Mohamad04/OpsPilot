import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { getAccessToken } from "../auth/auth";
import { addOrganisationMember, changeOrganisationMemberRole, getOrganisationMembers, removeOrganisationMember } from "../api/organisationMemberApi";
import { organisationMemberKeys, useOrganisationMembers, useOrganisationMembershipWrites } from "../hooks/useOrganisationMembers";
import { ORG, OTHER_ORG, USER, KEY, INCIDENT } from "./incidentFixtures";

vi.mock("../auth/auth", () => ({ getAccessToken: vi.fn(), endSession: vi.fn() }));
const fetchMock = vi.fn();
const member = { id: KEY, organisationId: ORG, userId: USER, displayName: "Demo member", role: "ENGINEER" };
const page = (content = [member]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, first: true, last: true });
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
beforeEach(() => { vi.clearAllMocks(); vi.stubGlobal("fetch", fetchMock); vi.mocked(getAccessToken).mockResolvedValue("membership-test-token"); });
afterEach(() => vi.unstubAllGlobals());

test("member directory uses authenticated bounded server pagination and cancellation", async () => {
    const controller = new AbortController(); fetchMock.mockResolvedValue(json(page()));
    expect((await getOrganisationMembers(ORG, 0, 20, controller.signal)).content).toEqual([member]);
    const [url, options] = fetchMock.mock.calls[0];
    expect(new URL(url).pathname).toBe(`/api/organisations/${ORG}/members`);
    expect(Object.fromEntries(new URL(url).searchParams)).toEqual({ page: "0", size: "20" });
    expect(new Headers(options.headers).get("Authorization")).toBe("Bearer membership-test-token");
    expect(options.signal).toBe(controller.signal);
});
test("member changes use local user and membership identifiers with actual API contracts", async () => {
    fetchMock.mockResolvedValueOnce(json(member, 201)).mockResolvedValueOnce(json({ ...member, role: "VIEWER" })).mockResolvedValueOnce(new Response(null, { status: 204 }));
    await addOrganisationMember(ORG, USER, "ENGINEER"); await changeOrganisationMemberRole(ORG, KEY, "VIEWER"); await removeOrganisationMember(ORG, KEY);
    expect(fetchMock.mock.calls.map(([, options]) => options.method)).toEqual(["POST", "PATCH", "DELETE"]);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ userId: USER, role: "ENGINEER" });
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ role: "VIEWER" });
    expect(new URL(fetchMock.mock.calls[2][0]).pathname).toBe(`/api/organisations/${ORG}/members/${KEY}`);
});
test.each([400, 403, 404, 409])("HTTP %s remains an explicit error", async status => {
    fetchMock.mockResolvedValue(json({ message: "private server details" }, status));
    await expect(addOrganisationMember(ORG, USER, "ENGINEER")).rejects.toMatchObject({ status });
});
test("foreign, duplicate, malformed and mismatched directory data fail closed", async () => {
    for (const response of [page([{ ...member, organisationId: OTHER_ORG }]), page([member, member]), page([{ ...member, role: "ROOT" }]), { ...page(), page: 1 }]) {
        fetchMock.mockResolvedValue(json(response)); await expect(getOrganisationMembers(ORG)).rejects.toMatchObject({ status: 502 });
    }
    fetchMock.mockResolvedValue(json({ ...member, id: INCIDENT }));
    await expect(changeOrganisationMemberRole(ORG, KEY, "ENGINEER")).rejects.toMatchObject({ status: 502 });
    fetchMock.mockResolvedValue(json({ ...member, userId: INCIDENT }));
    await expect(addOrganisationMember(ORG, USER, "ENGINEER")).rejects.toMatchObject({ status: 502 });
});
test("invalid identifiers and pagination never make requests", async () => {
    await expect(getOrganisationMembers("not-a-uuid")).rejects.toMatchObject({ status: 400 });
    await expect(getOrganisationMembers(ORG, -1)).rejects.toMatchObject({ status: 400 });
    await expect(getOrganisationMembers(ORG, 0, 101)).rejects.toMatchObject({ status: 400 });
    expect(fetchMock).not.toHaveBeenCalled();
});
function wrapper(client: QueryClient) {
    return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
test("directory switching cancels old requests and does not present a late foreign response", async () => {
    let complete!: (response: Response) => void;
    fetchMock.mockImplementationOnce(() => new Promise<Response>(resolve => { complete = resolve; }))
        .mockResolvedValueOnce(json(page([{ ...member, organisationId: OTHER_ORG }])));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result, rerender, unmount } = renderHook(({ org }) => useOrganisationMembers(org), { initialProps: { org: ORG }, wrapper: wrapper(client) });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const firstSignal = fetchMock.mock.calls[0][1].signal;
    rerender({ org: OTHER_ORG }); await waitFor(() => expect(result.current.data?.content[0].organisationId).toBe(OTHER_ORG));
    await act(async () => complete(json(page()))); expect(firstSignal.aborted).toBe(true);
    expect(result.current.data?.content[0].organisationId).toBe(OTHER_ORG);
    unmount(); client.clear();
});
test("access denial hides cached directory entries", async () => {
    const client = new QueryClient(); client.setQueryData(organisationMemberKeys.list(ORG, 0, 20), page());
    fetchMock.mockResolvedValue(json({}, 403));
    const { result, unmount } = renderHook(() => useOrganisationMembers(ORG), { wrapper: wrapper(client) });
    expect(result.current.data).toBeUndefined(); await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined(); unmount(); client.clear();
});
test("confirmed membership removal invalidates members, organisation permissions and incident assignment caches", async () => {
    const client = new QueryClient(); const invalidate = vi.spyOn(client, "invalidateQueries");
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const { result, unmount } = renderHook(() => useOrganisationMembershipWrites(ORG), { wrapper: wrapper(client) });
    await act(async () => result.current.remove.mutateAsync(KEY));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["organisation-members", ORG] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["organisations"] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["incidents", ORG] });
    expect(organisationMemberKeys.list(ORG, 0, 20)).not.toEqual(organisationMemberKeys.list(OTHER_ORG, 0, 20));
    unmount(); client.clear();
});
