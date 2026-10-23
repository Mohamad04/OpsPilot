import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import {BrowserRouter}  from "react-router";
import {QueryClient, QueryClientProvider} from "@tanstack/react-query";
import { OrganisationProvider } from "./context/OrganisationProvider";
import { initializeApplication } from "./auth/bootstrap";
import AuthenticationBoundary from "./auth/AuthenticationBoundary";

const queryClient = new QueryClient();
const rootElement = document.getElementById('root')!;
rootElement.textContent = "Connecting to Keycloak…";

function mountApplication(error?: unknown) {
    createRoot(rootElement).render(
        <StrictMode>
            <AuthenticationBoundary provisioningError={error instanceof Error ? error.message : undefined}>
                <QueryClientProvider client={queryClient}>
                    <OrganisationProvider>
                        <BrowserRouter>
                            <App />
                        </BrowserRouter>
                    </OrganisationProvider>
                </QueryClientProvider>
            </AuthenticationBoundary>
        </StrictMode>,
    );
}

// This module-level promise runs before React mounts, including in StrictMode.
void initializeApplication().then(() => mountApplication(), mountApplication);
