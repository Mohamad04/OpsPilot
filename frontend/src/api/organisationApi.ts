import type {Organisation} from "../types/Organisation";
import {API_BASE_URL} from "./config";
import type { CreateOrganisationRequest } from "../types/CreateOrganisationRequest";


export async function getOrganisation(organisationId: string): Promise<Organisation> {
    const url = `${API_BASE_URL}/api/organisations/${organisationId}`;
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error("Could not get organisation.");
    }
    const data: Organisation = await response.json();
    return data;
}

export async function getAllOrganisations(): Promise<Organisation[]> {
    const url = `${API_BASE_URL}/api/organisations`;
    const response = await fetch(url);
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

    const response = await fetch(url, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify(request),
    });

    if (!response.ok) {
        throw new Error("Could not create organisation.");
    }

    return response.json();
}