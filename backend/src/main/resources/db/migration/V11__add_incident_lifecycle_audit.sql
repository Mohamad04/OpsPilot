ALTER TABLE incidents ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);
ALTER TABLE incident_events ADD COLUMN previous_status VARCHAR(16);
ALTER TABLE incident_events ADD COLUMN new_status VARCHAR(16);
-- Legacy STATUS_CHANGED and COMMENT_ADDED records remain untouched, with null new metadata.
ALTER TABLE incident_events DROP CONSTRAINT incident_events_type_check;
ALTER TABLE incident_events ADD CONSTRAINT incident_events_type_check CHECK (event_type IN
    ('CREATED','STATUS_CHANGED','ASSIGNMENT_CHANGED','ALERT_LINKED','COMMENT_ADDED','ALERT_RESOLVED'));
ALTER TABLE incident_events DROP CONSTRAINT incident_events_system_source_check;
ALTER TABLE incident_events ADD CONSTRAINT incident_events_system_source_check CHECK (
    source_alert_id IS NULL OR (actor_user_id IS NULL AND event_type IN ('CREATED','ALERT_LINKED','COMMENT_ADDED','ALERT_RESOLVED')));
ALTER TABLE incident_events ADD CONSTRAINT incident_events_transition_metadata_check CHECK (
    (previous_status IS NULL AND new_status IS NULL)
    OR (event_type = 'STATUS_CHANGED' AND actor_user_id IS NOT NULL AND source_alert_id IS NULL
        AND previous_status IS NOT NULL AND new_status IS NOT NULL AND previous_status <> new_status
        AND previous_status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MITIGATED','RESOLVED','CLOSED')
        AND new_status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MITIGATED','RESOLVED','CLOSED')));
-- Prevent a second recovery receipt after upgrading from legacy system COMMENT_ADDED notes.
CREATE UNIQUE INDEX incident_events_one_alert_recovery_idx ON incident_events(organisation_id, source_alert_id)
    WHERE source_alert_id IS NOT NULL AND event_type IN ('COMMENT_ADDED','ALERT_RESOLVED');
