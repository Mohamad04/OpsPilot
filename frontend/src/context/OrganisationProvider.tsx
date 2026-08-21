import { useState } from "react";
import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";

import { getAllOrganisations } from "../api/organisationApi";
import { OrganisationContext } from "./OrganisationContext";

type OrganisationProviderProps = {
    children: ReactNode;
};

export function OrganisationProvider({
                                         children,
                                     }: OrganisationProviderProps) {
    const [
        currentOrganisationId,
        setCurrentOrganisationId,
    ] = useState<string | null>(null);

    const query = useQuery({
        queryKey: ["organisations"],
        queryFn: getAllOrganisations,
    });

    const organisationsPending = query.isPending;

    const organisationsError =
        query.isError
            ? query.error.message
            : null;

    const organisations = query.data ?? [];

    const currentOrganisation =
        organisations.find(
            (organisation) =>
                organisation.id ===
                currentOrganisationId
        )
        ?? organisations[0]
        ?? null;

    return (
        <OrganisationContext.Provider
            value={{
                organisations,
                currentOrganisation,
                setCurrentOrganisationId,
                organisationsPending,
                organisationsError,
            }}
        >
            {children}
        </OrganisationContext.Provider>
    );
}