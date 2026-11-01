package com.opspilot.backend.domain;

public enum IncidentStatus {
    OPEN,
    ACKNOWLEDGED,
    INVESTIGATING,
    MITIGATED,
    RESOLVED,
    CLOSED;

    public boolean isOngoing() {
        return switch (this) {
            case OPEN, ACKNOWLEDGED, INVESTIGATING, MITIGATED -> true;
            case RESOLVED, CLOSED -> false;
        };
    }

    public boolean canTransitionTo(IncidentStatus target) {
        if (target == null) return false;
        return switch (this) {
            case OPEN -> target == ACKNOWLEDGED;
            case ACKNOWLEDGED -> target == INVESTIGATING || target == RESOLVED;
            case INVESTIGATING -> target == MITIGATED || target == RESOLVED;
            case MITIGATED -> target == INVESTIGATING || target == RESOLVED;
            case RESOLVED -> target == CLOSED || target == INVESTIGATING;
            case CLOSED -> target == INVESTIGATING;
        };
    }
}
