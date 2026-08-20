import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import {BrowserRouter}  from "react-router";
import {QueryClient, QueryClientProvider} from "@tanstack/react-query";
import {OrganisationProvider} from "./context/OrganisationContext";

const queryClient = new QueryClient();
createRoot(document.getElementById('root')!).render(
  <StrictMode>
      <QueryClientProvider client={queryClient}>
          <OrganisationProvider>
              <BrowserRouter>
                  <App />
              </BrowserRouter>
          </OrganisationProvider>
      </QueryClientProvider>
  </StrictMode>,
)
