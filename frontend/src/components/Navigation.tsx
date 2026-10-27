import { NavLink } from "react-router";
import Icon from "./Icon";

export default function Navigation({ id, onNavigate }: { id?: string; onNavigate?: () => void }) {
    return (
        <nav id={id} className="navbar" aria-label="Main navigation">
            <ul>
                <li>
                    <NavLink
                        to="/"
                        end
                        className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}
                        onClick={onNavigate}
                        title="Dashboard"
                    >
                        <Icon name="dashboard" /><span className="nav-label">Dashboard</span>
                    </NavLink>
                </li>
                <li>
                    <NavLink
                        to="/services"
                        className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}
                        onClick={onNavigate}
                        title="Services"
                    >
                        <Icon name="services" /><span className="nav-label">Services</span>
                    </NavLink>
                </li>
                <li><NavLink to="/alerts" className={({ isActive }) => isActive ? "nav-link active" : "nav-link"} onClick={onNavigate} title="Alerts">
                    <Icon name="activity" /><span className="nav-label">Alerts</span>
                </NavLink></li>
            </ul>
        </nav>
    );
}
