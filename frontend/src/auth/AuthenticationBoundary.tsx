import type { ReactNode } from "react";
import { logout } from "./auth";
import { useAuth } from "./useAuth";
import PageState from "../components/PageState";

export default function AuthenticationBoundary({ children, provisioningError }: {
    children: ReactNode;
    provisioningError?: string;
}) {
    const auth = useAuth();
    if (auth.status === "authenticated") {
        if (!provisioningError) return children;
        return (
            <main className="auth-shell">
                <PageState variant="error" title="OpsPilot account setup" action={<>
                    <button type="button" onClick={() => window.location.reload()}>Retry account setup</button>
                    <button type="button" onClick={() => { void logout(); }}>Logout</button>
                </>}>{provisioningError}</PageState>
            </main>
        );
    }

    return (
        <main className="auth-shell">
            {auth.status === "logging-out" ? <PageState variant="loading" title="OpsPilot authentication">Signing out of Keycloak…</PageState> : (
                <PageState variant="error" title="OpsPilot authentication" action={
                    auth.status === "logout-error" ? (
                        <button type="button" onClick={() => { void logout(); }}>Retry logout</button>
                    ) : (
                        <button type="button" onClick={() => window.location.reload()}>
                            {auth.status === "unauthenticated" ? "Sign in with Keycloak" : "Retry sign in"}
                        </button>
                    )
                }>{auth.message ?? "Connecting to Keycloak…"}</PageState>
            )}
        </main>
    );
}
