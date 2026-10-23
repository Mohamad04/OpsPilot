import { useOrganisation } from "../hooks/useOrganisation";
import { useAuth } from "../auth/useAuth";
import { logout } from "../auth/auth";
import { Link } from "react-router";
import { useEffect, useRef, useState } from "react";
import type { Ref } from "react";
import Brand from "./Brand";
import Icon from "./Icon";

type HeaderProps = {
    navigationOpen: boolean;
    onOpenNavigation: () => void;
    navigationButtonRef: Ref<HTMLButtonElement>;
};

export default function Header({ navigationOpen, onOpenNavigation, navigationButtonRef }: HeaderProps) {
    const { user } = useAuth();
    const [accountOpen, setAccountOpen] = useState(false);
    const accountRef = useRef<HTMLDivElement>(null);
    const accountButtonRef = useRef<HTMLButtonElement>(null);
    const displayName = user?.displayName?.trim() || "OpsPilot user";
    const initials = displayName.trim().split(/\s+/).slice(0, 2).map((part) => part[0]).join("").toUpperCase();

    useEffect(() => {
        if (!accountOpen) return;
        const outside = (event: PointerEvent) => {
            if (event.target instanceof Node && !accountRef.current?.contains(event.target)) setAccountOpen(false);
        };
        const escape = (event: KeyboardEvent) => {
            if (event.key === "Escape") {
                event.preventDefault();
                setAccountOpen(false);
                accountButtonRef.current?.focus();
            }
        };
        document.addEventListener("pointerdown", outside);
        document.addEventListener("keydown", escape);
        return () => {
            document.removeEventListener("pointerdown", outside);
            document.removeEventListener("keydown", escape);
        };
    }, [accountOpen]);
    const {
        organisations,
        currentOrganisation,
        setCurrentOrganisationId,
        organisationsPending,
    } = useOrganisation();

    return (
        <header className="header">
            <div className="header-inner">
                <div className="header-leading">
                    <button ref={navigationButtonRef} type="button" className="icon-button navigation-trigger"
                        aria-label="Open navigation" aria-expanded={navigationOpen} aria-controls="mobile-navigation" onClick={onOpenNavigation}>
                        <Icon name="menu" />
                    </button>
                    <Brand />
                </div>
                <div className="organisation-controls">
                    <label className="organisation-field">
                        <span className="control-label">Organisation</span>
                        <select
                            aria-label="Organisation"
                            disabled={organisations.length === 0}
                            className="organisation-selector"
                            value={currentOrganisation?.id ?? ""}
                            title={currentOrganisation?.name ?? "Organisation"}
                            onChange={(event) => setCurrentOrganisationId(event.target.value)}
                        >
                            {organisations.length === 0 && (
                                <option value="">{organisationsPending ? "Loading organisations..." : "No organisations yet"}</option>
                            )}
                            {organisations.map((organisation) => (
                                <option key={organisation.id} value={organisation.id}>
                                    {organisation.name}
                                </option>
                            ))}
                        </select>
                    </label>
                    <Link to="/organisations/new" className="button button-secondary create-organisation-action" aria-label="Create Organisation" title="Create Organisation">
                        <Icon name="plus" /><span className="action-label">Create Organisation</span>
                    </Link>
                </div>
                <div className="account" ref={accountRef} onBlur={(event) => {
                    if (!event.currentTarget.contains(event.relatedTarget)) setAccountOpen(false);
                }}>
                    <button ref={accountButtonRef} type="button" className="account-trigger" aria-label={`Account for ${displayName}`}
                        aria-expanded={accountOpen} aria-controls="account-actions" onClick={() => setAccountOpen(!accountOpen)}>
                        <span className="avatar" aria-hidden="true">{initials}</span>
                        <span className="account-identity"><span className="header-user" title={displayName}>{displayName}</span><span className="account-caption">Your account</span></span>
                        <Icon name="chevronDown" className="account-chevron" />
                    </button>
                    {accountOpen && (
                        <div id="account-actions" className="account-panel" role="group" aria-label="Account actions">
                            <div className="account-profile"><strong>{displayName}</strong>{user?.email && <span>{user.email}</span>}</div>
                            <button type="button" className="button button-danger-quiet" onClick={() => { void logout(); }}><Icon name="logout" />Logout</button>
                        </div>
                    )}
                </div>
            </div>
        </header>
    );
}
