import { beforeEach, expect, test, vi } from "vitest";

const fixture = vi.hoisted(() => ({
    authenticate: vi.fn(), provision: vi.fn(), createRoot: vi.fn(), render: vi.fn(),
}));
vi.mock("../auth/auth", () => ({
    initializeAuth: fixture.authenticate, getAccessToken: vi.fn(), endSession: vi.fn(),
    getAuthState: vi.fn(), subscribeToAuth: vi.fn(), logout: vi.fn(),
}));
vi.mock("../api/meApi", () => ({ provisionCurrentUser: fixture.provision }));
vi.mock("react-dom/client", () => ({ createRoot: fixture.createRoot }));

beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    document.body.innerHTML = '<div id="root"></div>';
    fixture.createRoot.mockReturnValue({ render: fixture.render });
    fixture.authenticate.mockResolvedValue(undefined);
    fixture.provision.mockResolvedValue(undefined);
});

test("startup is shared and React cannot mount providers before authentication and provisioning finish", async () => {
    let finishAuth: () => void = () => { throw new Error("Authentication did not start"); };
    let finishProvisioning: () => void = () => { throw new Error("Provisioning did not start"); };
    fixture.authenticate.mockImplementation(() => new Promise<void>((resolve) => { finishAuth = resolve; }));
    fixture.provision.mockImplementation(() => new Promise<void>((resolve) => { finishProvisioning = resolve; }));
    await import("../main");
    const { initializeApplication } = await import("../auth/bootstrap");
    const first = initializeApplication();
    expect(initializeApplication()).toBe(first);
    expect(fixture.authenticate).toHaveBeenCalledTimes(1);
    expect(fixture.provision).not.toHaveBeenCalled();
    expect(fixture.createRoot).not.toHaveBeenCalled();
    finishAuth();
    await vi.waitFor(() => expect(fixture.provision).toHaveBeenCalledTimes(1));
    expect(fixture.createRoot).not.toHaveBeenCalled();
    finishProvisioning();
    await first;
    await vi.waitFor(() => expect(fixture.render).toHaveBeenCalledTimes(1));
    expect(fixture.createRoot).toHaveBeenCalledTimes(1);
    expect(fixture.render.mock.calls[0][0].props.children.props.provisioningError).toBeUndefined();
});

test("provisioning failure is cached and mounts a recovery screen without retrying authentication", async () => {
    fixture.provision.mockRejectedValue(new Error("Verified email required"));
    await import("../main");
    await vi.waitFor(() => expect(fixture.render).toHaveBeenCalledTimes(1));
    const { initializeApplication } = await import("../auth/bootstrap");
    await expect(initializeApplication()).rejects.toThrow("Verified email required");
    expect(fixture.authenticate).toHaveBeenCalledTimes(1);
    expect(fixture.provision).toHaveBeenCalledTimes(1);
    expect(fixture.render.mock.calls[0][0].props.children.props.provisioningError).toBe("Verified email required");
});

test("authentication failure cannot call provisioning", async () => {
    fixture.authenticate.mockRejectedValue(new Error("Authentication initialization failed"));
    await import("../main");
    await vi.waitFor(() => expect(fixture.render).toHaveBeenCalledTimes(1));
    expect(fixture.provision).not.toHaveBeenCalled();
});
