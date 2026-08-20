import {createContext,
        useState} from "react";
import type {ReactNode} from "react";
import {useQuery} from "@tanstack/react-query";

import type {Organisation} from "../types/Organisation";
import {getAllOrganisations} from "../api/organisationApi";

type OrganisationContextType = {
    organisations: Organisation[];
    currentOrganisation: Organisation | null;
    setCurrentOrganisationId: (organisationId: string) => void;
    organisationsPending: boolean;
    organisationsError: string | null;
};

export const OrganisationContext =
    createContext<OrganisationContextType | undefined>(undefined);

type OrganisationProviderProps = {
    children: ReactNode;
};

export function OrganisationProvider({
                                         children,
                                     }: OrganisationProviderProps) {
    const [currentOrganisationId, setCurrentOrganisationId] =
        useState<string | null>(null);
    const query = useQuery({
        queryKey: ["organisations"],
        queryFn: getAllOrganisations
    });
    const organisationsPending = query.isPending;
    const organisations = query.data ?? [];
    const currentOrganisation =
        organisations.find(
            (organisation) =>
                organisation.id === currentOrganisationId
        )
        ?? organisations[0]
        ?? null;
    const organisationsError = query.isError? query.error.message : null;

    return (
            <OrganisationContext.Provider
                value={{
                    organisations,
                    currentOrganisation,
                    setCurrentOrganisationId,
                    organisationsPending,
                    organisationsError
                    }}>
                {children}
            </OrganisationContext.Provider>
    );
}