import { apiFetch, ApiError } from "./client";
import { API_BASE_URL } from "./config";

export async function provisionCurrentUser(): Promise<void> {
    let response: Response;
    try {
        response = await apiFetch(`${API_BASE_URL}/api/me/provision`, { method: "POST" });
    } catch (error) {
        if (error instanceof ApiError && error.status === 403) {
            throw new Error("Account setup was refused. Verify your Keycloak email and ask an administrator to check your OpsPilot account status.", { cause: error });
        }
        if (error instanceof ApiError && error.status === 401) throw error;
        throw new Error("Unable to prepare your OpsPilot account. Check that the backend is available, then retry.", { cause: error });
    }
    if (response.status === 409) {
        throw new Error("Unable to prepare your OpsPilot account because of an account conflict. Contact an administrator; accounts are never linked automatically by email.");
    }
    if (!response.ok) {
        throw new Error("Unable to prepare your OpsPilot account. Please retry or contact an administrator.");
    }
}
