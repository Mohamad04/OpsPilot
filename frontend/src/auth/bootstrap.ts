import { provisionCurrentUser } from "../api/meApi";
import { initializeAuth } from "./auth";

let initialization: Promise<void> | undefined;

// One startup sequence per page, outside React effects and StrictMode remounts.
export function initializeApplication(): Promise<void> {
    initialization ??= initializeAuth().then(provisionCurrentUser);
    return initialization;
}
