import { NavLink } from "react-router";

export default function Navigation() {
    return (
        <nav className="navbar">
            <NavLink
                to="/"
                end
                className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}
            >
                Dashboard
            </NavLink>

            <NavLink
                to="/services"
                className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}
            >
                Services
            </NavLink>
        </nav>
    );
}