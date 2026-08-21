import { createContext } from "react";
import type { Organisation } from "../types/Organisation";

export type OrganisationContextType = {
    organisations: Organisation[];
    currentOrganisation: Organisation | null;
    setCurrentOrganisationId: (organisationId: string) => void;
    organisationsPending: boolean;
    organisationsError: string | null;
};

export const OrganisationContext =
    createContext<OrganisationContextType | undefined>(
        undefined
    );