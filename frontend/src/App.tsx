import { Route, Routes } from "react-router";
import { lazy, Suspense } from "react";
import PageState from "./components/PageState";
import DashboardPage from "./pages/DashboardPage";
import ServicesPage from "./pages/ServicesPage";
import AppLayout from "./layouts/AppLayout";
import ServiceDetailsPage from "./pages/ServiceDetailsPage";
import AddServicePage from "./pages/AddServicePage";
import CreateOrganisationPage from "./pages/CreateOrganisationPage";
import AlertsPage from "./pages/AlertsPage";
import AlertDetailsPage from "./pages/AlertDetailsPage";
const IncidentsPage = lazy(() => import("./pages/IncidentsPage"));
const IncidentDetailsPage = lazy(() => import("./pages/IncidentDetailsPage"));

function App() {
  return (
      <Routes>
        <Route element={<AppLayout />}>
            <Route index element={<DashboardPage />} />
            <Route path="services" element={<ServicesPage />} />
            <Route path="services/new" element={<AddServicePage/>} />
            <Route path="services/:id" element={<ServiceDetailsPage />} />
            <Route path="alerts" element={<AlertsPage />} />
            <Route path="alerts/:alertId" element={<AlertDetailsPage />} />
            <Route path="incidents" element={<Suspense fallback={<PageState variant="loading" title="Loading incidents..." />}><IncidentsPage /></Suspense>} />
            <Route path="incidents/:incidentId" element={<Suspense fallback={<PageState variant="loading" title="Loading incident details..." />}><IncidentDetailsPage /></Suspense>} />
            <Route path="organisations/new" element={<CreateOrganisationPage />} />
        </Route>
      </Routes>
  );
}

export default App;
