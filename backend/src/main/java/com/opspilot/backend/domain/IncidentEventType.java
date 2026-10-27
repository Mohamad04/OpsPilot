package com.opspilot.backend.domain;

/** Timeline vocabulary only. No transition handlers or automatic event generation in Phase 4A. */
public enum IncidentEventType {
    CREATED,
    STATUS_CHANGED,
    ASSIGNMENT_CHANGED,
    ALERT_LINKED,
    COMMENT_ADDED
}
