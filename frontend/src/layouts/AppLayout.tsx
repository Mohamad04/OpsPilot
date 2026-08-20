import { Outlet } from "react-router";
import Sidebar from "../components/Sidebar";
import Header from "../components/Header";

export default function AppLayout() {
    return (
        <div className="app-layout">
            <Sidebar />

            <div className="app-content">
                <Header />

                <main className="main-content">
                    <Outlet />
                </main>
            </div>
        </div>
    );
}