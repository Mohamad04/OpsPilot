import { Link, Outlet, useMatch } from "react-router";
import Sidebar from "../components/Sidebar";
import Header from "../components/Header";
import { useOrganisation } from "../hooks/useOrganisation";
import { useCallback, useEffect, useRef, useState } from "react";
import PageState from "../components/PageState";

export default function AppLayout() {
    const [navigationOpen, setNavigationOpen] = useState(false);
    const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
    const drawerRef = useRef<HTMLDialogElement>(null);
    const navigationButtonRef = useRef<HTMLButtonElement>(null);
    const closeNavigation = useCallback(() => {
        if (drawerRef.current?.open) drawerRef.current.close();
        setNavigationOpen(false);
        navigationButtonRef.current?.focus();
    }, []);

    useEffect(() => {
        const desktop = window.matchMedia("(min-width: 48rem)");
        const resize = () => {
            if (desktop.matches && drawerRef.current?.open) {
                closeNavigation();
                document.getElementById("main-content")?.focus();
            }
        };
        const onHistoryChange = () => {
            if (drawerRef.current?.open) closeNavigation();
        };
        desktop.addEventListener("change", resize);
        window.addEventListener("popstate", onHistoryChange);
        return () => {
            desktop.removeEventListener("change", resize);
            window.removeEventListener("popstate", onHistoryChange);
        };
    }, [closeNavigation]);

    useEffect(() => {
        if (!navigationOpen) return;
        const previous = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        return () => { document.body.style.overflow = previous; };
    }, [navigationOpen]);

    const { organisations, organisationsPending, organisationsError } = useOrganisation();
    const isCreatingOrganisation = useMatch("/organisations/new") !== null;
    const needsOrganisation = !organisationsPending && !organisationsError && organisations.length === 0;
    return (
        <div className="app-layout" data-sidebar-collapsed={sidebarCollapsed}>
            <a className="skip-link" href="#main-content">Skip to content</a>
            <Sidebar collapsed={sidebarCollapsed} onToggleCollapsed={() => setSidebarCollapsed(!sidebarCollapsed)} drawerRef={drawerRef} onCloseNavigation={closeNavigation} />

            <div className="app-content">
                <Header navigationOpen={navigationOpen} navigationButtonRef={navigationButtonRef} onOpenNavigation={() => {
                    if (drawerRef.current && !drawerRef.current.open) drawerRef.current.showModal();
                    setNavigationOpen(true);
                }} />

                <main className="main-content" id="main-content" tabIndex={-1}>
                    <div className="page-container">
                        {organisationsPending && !isCreatingOrganisation ? <PageState variant="loading" title="Loading organisations..." />
                            : organisationsError && !isCreatingOrganisation ? <PageState variant="error" title="Unable to load organisations">{organisationsError}</PageState>
                            : needsOrganisation && !isCreatingOrganisation ? (
                                <PageState variant="empty" title="Welcome to OpsPilot" action={<Link to="/organisations/new" className="button button-primary">Create Organisation</Link>}>
                                    Create your first organisation to start monitoring services with your team.
                                </PageState>
                            ) : <Outlet />}
                    </div>
                </main>
            </div>
        </div>
    );
}
