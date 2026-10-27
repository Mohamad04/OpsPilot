-- Existing alert rows are not rewritten; this key supports tenant-safe incident links.
ALTER TABLE alerts ADD CONSTRAINT alerts_identity_organisation_key UNIQUE (id, organisation_id);

CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    severity VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assigned_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT incidents_identity_organisation_key UNIQUE (id, organisation_id),
    CONSTRAINT incidents_organisation_fk FOREIGN KEY (organisation_id) REFERENCES organisations(id),
    -- Membership removal unassigns the incident without deleting it or retaining access.
    CONSTRAINT incidents_assignee_membership_fk FOREIGN KEY (organisation_id, assigned_user_id)
        REFERENCES organisation_memberships(organisation_id, user_id)
        ON DELETE SET NULL (assigned_user_id),
    CONSTRAINT incidents_title_check CHECK (title ~ '[^[:space:]]'),
    CONSTRAINT incidents_description_check CHECK (description ~ '[^[:space:]]'),
    CONSTRAINT incidents_severity_check CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT incidents_status_check CHECK (status IN
        ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'MITIGATED', 'RESOLVED', 'CLOSED')),
    CONSTRAINT incidents_timestamps_check CHECK (
        (acknowledged_at IS NULL OR acknowledged_at >= created_at)
        AND (resolved_at IS NULL OR (acknowledged_at IS NOT NULL AND resolved_at >= acknowledged_at))),
    CONSTRAINT incidents_lifecycle_check CHECK (
        (status = 'OPEN' AND acknowledged_at IS NULL AND resolved_at IS NULL)
        OR (status IN ('ACKNOWLEDGED', 'INVESTIGATING', 'MITIGATED')
            AND acknowledged_at IS NOT NULL AND resolved_at IS NULL)
        OR (status IN ('RESOLVED', 'CLOSED') AND acknowledged_at IS NOT NULL AND resolved_at IS NOT NULL))
);

CREATE INDEX incidents_organisation_history_idx ON incidents(organisation_id, created_at DESC, id DESC);
CREATE INDEX incidents_organisation_status_idx ON incidents(organisation_id, status, created_at DESC, id DESC);
CREATE INDEX incidents_assignee_idx ON incidents(organisation_id, assigned_user_id) WHERE assigned_user_id IS NOT NULL;

CREATE TABLE incident_alerts (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    incident_id UUID NOT NULL,
    alert_id UUID NOT NULL,
    CONSTRAINT incident_alerts_incident_fk FOREIGN KEY (incident_id, organisation_id)
        REFERENCES incidents(id, organisation_id),
    CONSTRAINT incident_alerts_alert_fk FOREIGN KEY (alert_id, organisation_id)
        REFERENCES alerts(id, organisation_id),
    CONSTRAINT incident_alerts_pair_key UNIQUE (incident_id, alert_id)
);
CREATE INDEX incident_alerts_incident_idx ON incident_alerts(organisation_id, incident_id, id);
CREATE INDEX incident_alerts_alert_idx ON incident_alerts(organisation_id, alert_id);

CREATE TABLE incident_events (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    incident_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_user_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    details VARCHAR(4000) NOT NULL,
    CONSTRAINT incident_events_incident_fk FOREIGN KEY (incident_id, organisation_id)
        REFERENCES incidents(id, organisation_id),
    -- Historical authorship survives removal of an organisation membership.
    CONSTRAINT incident_events_actor_fk FOREIGN KEY (actor_user_id) REFERENCES users(id),
    CONSTRAINT incident_events_type_check CHECK (event_type IN
        ('CREATED', 'STATUS_CHANGED', 'ASSIGNMENT_CHANGED', 'ALERT_LINKED', 'COMMENT_ADDED')),
    CONSTRAINT incident_events_details_check CHECK (details ~ '[^[:space:]]')
);
CREATE INDEX incident_events_timeline_idx ON incident_events(organisation_id, incident_id, occurred_at, id);
CREATE INDEX incident_events_actor_idx ON incident_events(actor_user_id) WHERE actor_user_id IS NOT NULL;

-- Check actor membership only on append, so future revocation does not erase/block audit history.
CREATE FUNCTION validate_incident_event_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE incident_created_at TIMESTAMPTZ;
BEGIN
    SELECT created_at INTO incident_created_at FROM incidents
        WHERE id = NEW.incident_id AND organisation_id = NEW.organisation_id;
    IF NEW.occurred_at < incident_created_at THEN
        RAISE EXCEPTION 'Incident event cannot precede incident creation' USING ERRCODE = '23514';
    END IF;
    IF NEW.actor_user_id IS NOT NULL THEN
        PERFORM 1 FROM organisation_memberships
            WHERE organisation_id = NEW.organisation_id AND user_id = NEW.actor_user_id FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Incident event actor must be an organisation member' USING ERRCODE = '23503';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER incident_events_validate_insert BEFORE INSERT ON incident_events
    FOR EACH ROW EXECUTE FUNCTION validate_incident_event_insert();

CREATE FUNCTION reject_incident_event_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Incident events are append-only' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER incident_events_no_mutation BEFORE UPDATE OR DELETE ON incident_events
    FOR EACH ROW EXECUTE FUNCTION reject_incident_event_mutation();
CREATE TRIGGER incident_events_no_truncate BEFORE TRUNCATE ON incident_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_incident_event_mutation();
ALTER TABLE incident_events ENABLE ALWAYS TRIGGER incident_events_validate_insert;
ALTER TABLE incident_events ENABLE ALWAYS TRIGGER incident_events_no_mutation;
ALTER TABLE incident_events ENABLE ALWAYS TRIGGER incident_events_no_truncate;
