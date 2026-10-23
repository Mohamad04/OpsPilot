import Keycloak from "keycloak-js";

export type AuthState = {
    status: "initializing" | "authenticated" | "unauthenticated" | "error" | "logging-out" | "logout-error";
    user: { subject: string; displayName: string; username?: string; email?: string } | null;
    message: string | null;
};

let keycloak: Keycloak | undefined;
let initialization: Promise<void> | undefined;
let refresh: Promise<string> | undefined;
let state: AuthState = { status: "initializing", user: null, message: null };
const listeners = new Set<() => void>();

function publish(next: AuthState) {
    state = next;
    listeners.forEach((listener) => listener());
}

export const getAuthState = () => state;
export function subscribeToAuth(listener: () => void) {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
}

export function endSession(message = "Your session has ended. Sign in again to continue.") {
    keycloak?.clearToken();
    publish({ status: "unauthenticated", user: null, message });
}

function publishUser() {
    const claims = keycloak?.tokenParsed;
    publish({
        status: "authenticated",
        user: {
            subject: claims?.sub ?? "",
            displayName: claims?.name || claims?.preferred_username || claims?.email || "OpsPilot user",
            username: claims?.preferred_username,
            email: claims?.email,
        },
        message: null,
    });
}

export function initializeAuth(): Promise<void> {
    initialization ??= (async () => {
        try {
            const url = import.meta.env.VITE_KEYCLOAK_URL;
            const realm = import.meta.env.VITE_KEYCLOAK_REALM;
            const clientId = import.meta.env.VITE_KEYCLOAK_CLIENT_ID;
            if (!url || !realm || !clientId) {
                throw new Error("Missing Keycloak configuration");
            }

            keycloak = new Keycloak({ url, realm, clientId });
            keycloak.onAuthLogout = () => {
                if (state.status !== "logging-out") publish({
                    status: "unauthenticated", user: null,
                    message: "Your session has ended. Sign in again to continue.",
                });
            };
            keycloak.onTokenExpired = () => { void getAccessToken().catch(() => {}); };

            const params = new URL(window.location.href).searchParams;
            const returningFromLogin = params.has("state") && (params.has("code") || params.has("error"));
            // Handle the callback before routing. Explicit login avoids the adapter's
            // automatic re-login on clearToken(), which can cause redirect loops.
            const authenticated = await keycloak.init({
                flow: "standard",
                pkceMethod: "S256",
                responseMode: "query",
                enableLogging: false,
                messageReceiveTimeout: 10000,
            });
            if (!authenticated) {
                // A lost/invalid callback state must not start another automatic login.
                if (returningFromLogin) throw new Error("Login callback was not accepted");
                await keycloak.login({ redirectUri: window.location.href });
                throw new Error("Authentication did not complete");
            }
            publishUser();
        } catch {
            keycloak?.clearToken();
            publish({
                status: "error", user: null,
                message: "Unable to sign in to OpsPilot. Check that Keycloak is available and the frontend URL, realm and public client are configured correctly, then retry.",
            });
            throw new Error("Authentication initialization failed");
        }
    })();
    return initialization;
}

export function getAccessToken(): Promise<string> {
    if (state.status !== "authenticated" || !keycloak?.authenticated) {
        return Promise.reject(new Error("Sign in to continue."));
    }
    // Share refresh work between concurrent queries. The adapter owns all tokens.
    refresh ??= (async () => {
        try {
            await keycloak!.updateToken(30);
            if (state.status !== "authenticated" || !keycloak!.authenticated || !keycloak!.token) {
                throw new Error("Session ended");
            }
            return keycloak!.token;
        } catch {
            // A refresh completing after logout must not restore an active session.
            if (state.status === "logging-out") keycloak?.clearToken();
            else endSession("Your session could not be refreshed. Sign in again to continue.");
            throw new Error("Your session could not be refreshed. Sign in again to continue.");
        }
    })().finally(() => { refresh = undefined; });
    return refresh;
}

export async function logout(): Promise<void> {
    if (!keycloak || state.status === "logging-out") return;
    publish({ status: "logging-out", user: null, message: null });
    try {
        // POST keeps the ID token hint out of the URL. A retry after clearing
        // tokens uses GET without a hint, allowing Keycloak's logout confirmation.
        const redirect = keycloak.logout({
            redirectUri: window.location.origin + "/",
            logoutMethod: keycloak.idToken ? "POST" : "GET",
        });
        keycloak.clearToken();
        await redirect;
    } catch {
        keycloak.clearToken();
        publish({
            status: "logout-error", user: null,
            message: "Keycloak logout could not complete. Retry logout to end the identity-provider session.",
        });
    }
}
