-- Nullable additions preserve immutable legacy events; no history is rewritten.
ALTER TABLE incident_events ADD COLUMN previous_assignee_user_id UUID REFERENCES users(id);
ALTER TABLE incident_events ADD COLUMN new_assignee_user_id UUID REFERENCES users(id);
ALTER TABLE incident_events ADD COLUMN client_request_id UUID;
ALTER TABLE incident_events ADD CONSTRAINT incident_events_assignment_metadata_check CHECK (
    (previous_assignee_user_id IS NULL AND new_assignee_user_id IS NULL)
    OR (event_type = 'ASSIGNMENT_CHANGED' AND source_alert_id IS NULL
        AND previous_assignee_user_id IS DISTINCT FROM new_assignee_user_id));
ALTER TABLE incident_events ADD CONSTRAINT incident_events_comment_request_check CHECK (
    client_request_id IS NULL OR (event_type = 'COMMENT_ADDED' AND actor_user_id IS NOT NULL AND source_alert_id IS NULL));
CREATE UNIQUE INDEX incident_events_comment_request_idx
    ON incident_events(organisation_id, incident_id, actor_user_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

-- V9's ON DELETE SET NULL remains authoritative. A referential-action unassignment
-- bypasses JPA, so increment its version and append a system event in the same transaction.
-- Application assignment writes already increment @Version and append their human event.
CREATE FUNCTION audit_incident_membership_unassignment() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE event_at TIMESTAMPTZ; latest_at TIMESTAMPTZ;
BEGIN
    SELECT max(occurred_at) INTO latest_at FROM incident_events
        WHERE organisation_id = OLD.organisation_id AND incident_id = OLD.id;
    event_at := GREATEST(clock_timestamp(), OLD.created_at, latest_at + INTERVAL '1 microsecond');
    NEW.version := OLD.version + 1;
    INSERT INTO incident_events(id, organisation_id, incident_id, event_type, actor_user_id,
        occurred_at, details, previous_assignee_user_id, new_assignee_user_id)
    VALUES (gen_random_uuid(), OLD.organisation_id, OLD.id, 'ASSIGNMENT_CHANGED', NULL,
        event_at, 'Assignee cleared automatically by membership removal.', OLD.assigned_user_id, NULL);
    RETURN NEW;
END;
$$;
CREATE TRIGGER incidents_membership_unassignment BEFORE UPDATE OF assigned_user_id ON incidents
    FOR EACH ROW WHEN (OLD.assigned_user_id IS NOT NULL AND NEW.assigned_user_id IS NULL AND NEW.version = OLD.version)
    EXECUTE FUNCTION audit_incident_membership_unassignment();
