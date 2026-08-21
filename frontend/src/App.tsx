import { Route, Routes } from "react-router";
import DashboardPage from "./pages/DashboardPage";
import ServicesPage from "./pages/ServicesPage";
import AppLayout from "./layouts/AppLayout";
import ServiceDetailsPage from "./pages/ServiceDetailsPage";
import AddServicePage from "./pages/AddServicePage";
import CreateOrganisationPage from "./pages/CreateOrganisationPage";

function App() {
  return (
      <Routes>
        <Route element={<AppLayout />}>
            <Route index element={<DashboardPage />} />
            <Route path="services" element={<ServicesPage />} />
            <Route path="services/new" element={<AddServicePage/>} />
            <Route path="services/:id" element={<ServiceDetailsPage />} />
            <Route path="organisations/new" element={<CreateOrganisationPage />} />
        </Route>
      </Routes>
  );
}

export default App;