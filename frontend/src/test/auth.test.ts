import { beforeEach, expect, test, vi } from "vitest";

const { adapter, createKeycloak } = vi.hoisted(() => {
    const adapter = {
        init: vi.fn(), login: vi.fn(), logout: vi.fn(), updateToken: vi.fn(), clearToken: vi.fn(),
        authenticated: true, token: "test-access-token", idToken: "test-id-token",
        tokenParsed: { sub: "subject-123", name: "Alex Example", preferred_username: "alex", email: "alex@example.com" },
        onAuthLogout: undefined as (() => void) | undefined,
        onTokenExpired: undefined as (() => void) | undefined,
    };
    return { adapter, createKeycloak: vi.fn(function () { return adapter; }) };
});

vi.mock("keycloak-js", () => ({ default: createKeycloak }));

beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    window.history.replaceState(null, "", "/");
    vi.stubEnv("VITE_KEYCLOAK_URL", "http://localhost:8082");
    vi.stubEnv("VITE_KEYCLOAK_REALM", "opspilot");
    vi.stubEnv("VITE_KEYCLOAK_CLIENT_ID", "opspilot-frontend");
    adapter.authenticated = true;
    adapter.token = "test-access-token";
    adapter.idToken = "test-id-token";
    adapter.init.mockResolvedValue(true);
    adapter.updateToken.mockResolvedValue(false);
    adapter.login.mockImplementation(() => new Promise(() => {}));
    adapter.logout.mockImplementation(() => new Promise(() => {}));
    adapter.clearToken.mockImplementation(() => {
        adapter.authenticated = false;
        adapter.token = "";
        adapter.idToken = "";
        adapter.onAuthLogout?.();
    });
});

test("initializes one adapter once and exposes the callback user's identity", async () => {
    const auth = await import("../auth/auth");
    const first = auth.initializeAuth();
    expect(auth.initializeAuth()).toBe(first);
    await first;
    expect(createKeycloak).toHaveBeenCalledExactlyOnceWith({
        url: "http://localhost:8082", realm: "opspilot", clientId: "opspilot-frontend",
    });
    expect(adapter.init).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
        flow: "standard", pkceMethod: "S256", responseMode: "query", enableLogging: false,
    }));
    expect(auth.getAuthState()).toMatchObject({
        status: "authenticated", user: { subject: "subject-123", displayName: "Alex Example" },
    });
    expect(adapter.login).not.toHaveBeenCalled();
});

test("unauthenticated initialization invokes hosted login with the current deep link", async () => {
    window.history.replaceState(null, "", "/services/service-123?tab=details");
    adapter.init.mockResolvedValue(false);
    const auth = await import("../auth/auth");
    void auth.initializeAuth();
    await vi.waitFor(() => expect(adapter.login).toHaveBeenCalledExactlyOnceWith({ redirectUri: window.location.href }));
    expect(auth.getAuthState().status).toBe("initializing");
});

test("an unaccepted login callback stops instead of automatically starting another login", async () => {
    window.history.replaceState(null, "", "/services?code=test-code&state=unknown-state");
    adapter.init.mockResolvedValue(false);
    const auth = await import("../auth/auth");
    await expect(auth.initializeAuth()).rejects.toThrow("Authentication initialization failed");
    expect(adapter.login).not.toHaveBeenCalled();
    expect(auth.getAuthState().status).toBe("error");
});

test("callback or configuration failure stops authentication without a redirect loop", async () => {
    adapter.init.mockRejectedValue(new Error("invalid callback"));
    const auth = await import("../auth/auth");
    await expect(auth.initializeAuth()).rejects.toThrow("Authentication initialization failed");
    await expect(auth.initializeAuth()).rejects.toThrow("Authentication initialization failed");
    expect(adapter.init).toHaveBeenCalledTimes(1);
    expect(adapter.login).not.toHaveBeenCalled();
    expect(auth.getAuthState()).toMatchObject({ status: "error", user: null });
});

test("missing environment configuration fails before constructing an adapter", async () => {
    vi.stubEnv("VITE_KEYCLOAK_CLIENT_ID", "");
    const auth = await import("../auth/auth");
    await expect(auth.initializeAuth()).rejects.toThrow();
    expect(createKeycloak).not.toHaveBeenCalled();
    expect(auth.getAuthState().status).toBe("error");
});

test("concurrent requests share refresh and receive the refreshed token", async () => {
    const auth = await import("../auth/auth");
    await auth.initializeAuth();
    adapter.updateToken.mockImplementation(async () => { adapter.token = "refreshed-test-token"; return true; });
    const first = auth.getAccessToken();
    expect(auth.getAccessToken()).toBe(first);
    await expect(first).resolves.toBe("refreshed-test-token");
    expect(adapter.updateToken).toHaveBeenCalledExactlyOnceWith(30);
});

test("refresh failure removes the session and blocks subsequent requests without automatic login", async () => {
    const auth = await import("../auth/auth");
    await auth.initializeAuth();
    adapter.updateToken.mockRejectedValue(new Error("refresh rejected"));
    await expect(auth.getAccessToken()).rejects.toThrow("could not be refreshed");
    await expect(auth.getAccessToken()).rejects.toThrow("Sign in");
    expect(auth.getAuthState()).toMatchObject({ status: "unauthenticated", user: null });
    expect(adapter.token).toBe("");
    expect(adapter.login).not.toHaveBeenCalled();
});

test("expiry refreshes tokens and identity-provider logout notifies subscribers", async () => {
    const auth = await import("../auth/auth");
    await auth.initializeAuth();
    adapter.onTokenExpired?.();
    await vi.waitFor(() => expect(adapter.updateToken).toHaveBeenCalledWith(30));
    const listener = vi.fn();
    const unsubscribe = auth.subscribeToAuth(listener);
    adapter.clearToken();
    expect(listener).toHaveBeenCalledTimes(1);
    expect(auth.getAuthState().status).toBe("unauthenticated");
    unsubscribe();
});

test("logout clears tokens immediately, blocks requests and redirects via Keycloak", async () => {
    const auth = await import("../auth/auth");
    await auth.initializeAuth();
    void auth.logout();
    expect(adapter.logout).toHaveBeenCalledExactlyOnceWith({ redirectUri: window.location.origin + "/", logoutMethod: "POST" });
    expect(auth.getAuthState().status).toBe("logging-out");
    expect(adapter.token).toBe("");
    await expect(auth.getAccessToken()).rejects.toThrow("Sign in");
});

test("failed logout keeps the application inaccessible and offers recovery", async () => {
    const auth = await import("../auth/auth");
    await auth.initializeAuth();
    adapter.logout.mockRejectedValue(new Error("logout failed"));
    await auth.logout();
    expect(auth.getAuthState()).toMatchObject({ status: "logout-error", user: null });
    expect(auth.getAuthState().message).toContain("Retry logout");
    adapter.logout.mockImplementation(() => new Promise(() => {}));
    void auth.logout();
    expect(adapter.logout).toHaveBeenLastCalledWith({ redirectUri: window.location.origin + "/", logoutMethod: "GET" });
});

test("a refresh completing after logout cannot restore tokens or protected access", async () => {
    const auth = await import("../auth/auth");
    await auth.initializeAuth();
    let finishRefresh: () => void = () => { throw new Error("Refresh did not start"); };
    adapter.updateToken.mockImplementation(() => new Promise<boolean>((resolve) => {
        finishRefresh = () => {
            adapter.authenticated = true;
            adapter.token = "late-test-token";
            resolve(true);
        };
    }));
    const pending = auth.getAccessToken();
    void auth.logout();
    finishRefresh();
    await expect(pending).rejects.toThrow();
    expect(adapter.token).toBe("");
    expect(auth.getAuthState().status).toBe("logging-out");
});
