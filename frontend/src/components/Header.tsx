import { useOrganisation } from "../hooks/useOrganisation";

export default function Header() {
    const {
        organisations,
        currentOrganisation,
        setCurrentOrganisationId,
    } = useOrganisation();

    return (
        <header className="header">
            <span className="header-title">
                OpsPilot Console
            </span>

            <select
                className="organisation-selector"
                value={currentOrganisation?.id ?? ""}
                onChange={(event) =>
                    setCurrentOrganisationId(event.target.value)
                }
            >
                {organisations.map((organisation) => (
                    <option
                        key={organisation.id}
                        value={organisation.id}
                    >
                        {organisation.name}
                    </option>
                ))}
            </select>

            <span className="header-user">
                User/Login
            </span>
        </header>
    );
}