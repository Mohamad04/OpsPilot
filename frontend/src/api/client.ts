import { endSession, getAccessToken } from "../auth/auth";
import { API_BASE_URL } from "./config";

export class ApiError extends Error {
    readonly status: number;
    readonly errors: Record<string, string>;

    constructor(status: number, message: string, errors: Record<string, string> = {}) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.errors = errors;
    }
}

export async function apiFetch(url: string, options: RequestInit = {}): Promise<Response> {
    // Never send credentials to a URL outside the configured backend API.
    const base = new URL(API_BASE_URL);
    const target = new URL(url);
    if (target.origin !== base.origin || !target.pathname.startsWith("/api/")) {
        throw new Error("Invalid backend API URL");
    }
    const token = await getAccessToken();
    const headers = new Headers(options.headers);
    headers.set("Authorization", `Bearer ${token}`);
    const response = await fetch(url, { ...options, headers, redirect: "error" });
    if (response.status === 401) {
        endSession("The API rejected your session. Sign in again; if this persists, check the backend issuer and audience configuration.");
        throw new ApiError(401, "Authentication required. Sign in again.");
    }
    if (response.status === 403) {
        throw new ApiError(403, "You do not have permission to perform this action in this organisation.");
    }
    // Leave all other responses to the existing API-specific error handlers.
    return response;
}
