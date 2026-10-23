import type {Organisation} from "../types/Organisation";
import {API_BASE_URL} from "./config";
import { apiFetch, ApiError } from "./client";
import type { CreateOrganisationRequest } from "../types/CreateOrganisationRequest";


export async function getOrganisation(organisationId: string): Promise<Organisation> {
    const url = `${API_BASE_URL}/api/organisations/${organisationId}`;
    const response = await apiFetch(url);
    if (!response.ok) {
        throw new Error("Could not get organisation.");
    }
    const data: Organisation = await response.json();
    return data;
}

export async function getAllOrganisations(signal?: AbortSignal): Promise<Organisation[]> {
    const url = `${API_BASE_URL}/api/organisations`;
    const response = await apiFetch(url, { signal });
    if (!response.ok) {
        throw new Error("Could not get organisations.");
    }
    const data: Organisation[] = await response.json();
    return data;
}

export async function createOrganisation(
    request: CreateOrganisationRequest
): Promise<Organisation> {
    const url = `${API_BASE_URL}/api/organisations`;

    const response = await apiFetch(url, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify(request),
    });

    if (response.status === 409) {
        const message = "This organisation slug is already in use. Choose another slug.";
        throw new ApiError(409, message, { slug: message });
    }
    if (response.status === 400) {
        const errors: Record<string, string> = {};
        try {
            const body = await response.json();
            for (const field of ["name", "slug"]) {
                if (typeof body?.errors?.[field] === "string") errors[field] = body.errors[field];
            }
        } catch { /* Keep a useful fallback when the server response has no JSON body. */ }
        throw new ApiError(400, "Check the organisation name and slug, then try again.", errors);
    }
    if (!response.ok) throw new ApiError(response.status, "Could not create organisation. Please try again.");

    return response.json();
}
