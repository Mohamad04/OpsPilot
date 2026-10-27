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
}
