import { useSyncExternalStore } from "react";
import { getAuthState, subscribeToAuth } from "./auth";

export function useAuth() {
    return useSyncExternalStore(subscribeToAuth, getAuthState);
}
