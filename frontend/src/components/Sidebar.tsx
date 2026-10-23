import Navigation from './Navigation';
import type { RefObject } from "react";
import Brand from "./Brand";
import Icon from "./Icon";

type SidebarProps = {
    collapsed: boolean;
    onToggleCollapsed: () => void;
    drawerRef: RefObject<HTMLDialogElement | null>;
    onCloseNavigation: () => void;
};

export default function Sidebar({ collapsed, onToggleCollapsed, drawerRef, onCloseNavigation }: SidebarProps) {
    return (
        <>
        <aside className="sidebar" aria-label="Workspace sidebar">
            <Brand />
            <p className="sidebar-section">Workspace</p>
            <Navigation id="desktop-navigation" />
            <div className="sidebar-footer">
                <span className="sidebar-caption">Operations workspace</span>
                <button type="button" className="icon-button sidebar-toggle" onClick={onToggleCollapsed}
                    aria-label={collapsed ? "Expand navigation" : "Collapse navigation"}
                    aria-expanded={!collapsed} aria-controls="desktop-navigation" title={collapsed ? "Expand navigation" : "Collapse navigation"}>
                    <Icon name={collapsed ? "chevronRight" : "chevronLeft"} />
                </button>
            </div>
        </aside>
        <dialog ref={drawerRef} id="mobile-navigation" className="navigation-drawer" aria-labelledby="navigation-title"
            onClose={onCloseNavigation}
            onCancel={(event) => { event.preventDefault(); onCloseNavigation(); }}
            onKeyDown={(event) => {
                if (event.key === "Escape") { event.preventDefault(); onCloseNavigation(); }
            }}
            onClick={(event) => { if (event.target === event.currentTarget) onCloseNavigation(); }}>
            <div className="drawer-content">
                <div className="drawer-heading">
                    <h2 id="navigation-title">Navigation</h2>
                    <button type="button" className="icon-button" aria-label="Close navigation" onClick={onCloseNavigation} autoFocus>
                        <Icon name="close" />
                    </button>
                </div>
                <Brand onNavigate={onCloseNavigation} />
                <p className="sidebar-section">Workspace</p>
                <Navigation onNavigate={onCloseNavigation} />
            </div>
        </dialog>
        </>
    );
}
