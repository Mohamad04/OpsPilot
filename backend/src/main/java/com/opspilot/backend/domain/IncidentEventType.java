package com.opspilot.backend.domain;

/** Persisted timeline vocabulary; legacy event types remain readable. */
public enum IncidentEventType {
    CREATED,
    STATUS_CHANGED,
    ASSIGNMENT_CHANGED,
    ALERT_LINKED,
    COMMENT_ADDED,
    ALERT_RESOLVED
}
