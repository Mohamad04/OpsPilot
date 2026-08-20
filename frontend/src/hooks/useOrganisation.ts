import { useContext } from "react";
import { OrganisationContext } from "../context/OrganisationContext";

export function useOrganisation() {
    const context = useContext(OrganisationContext);

    if (context === undefined) {
        throw new Error(
            "useOrganisation must be used inside OrganisationProvider"
        );
    }

    return context;
}