-- Nullable for existing Phase 4A incidents; no historical incident or event is rewritten.
ALTER TABLE incidents ADD COLUMN monitored_service_id UUID;
ALTER TABLE incidents ADD CONSTRAINT incidents_service_organisation_fk
    FOREIGN KEY (monitored_service_id, organisation_id) REFERENCES monitored_services(id, organisation_id);

CREATE UNIQUE INDEX incidents_one_ongoing_service_idx
    ON incidents(organisation_id, monitored_service_id)
    WHERE monitored_service_id IS NOT NULL AND status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'MITIGATED');

ALTER TABLE incident_events ADD COLUMN source_alert_id UUID;
ALTER TABLE incident_events ADD CONSTRAINT incident_events_source_alert_fk
    FOREIGN KEY (source_alert_id, organisation_id) REFERENCES alerts(id, organisation_id);
ALTER TABLE incident_events ADD CONSTRAINT incident_events_system_source_check CHECK (
    source_alert_id IS NULL OR (actor_user_id IS NULL AND event_type IN ('CREATED', 'ALERT_LINKED', 'COMMENT_ADDED')));
-- A persisted alert may produce each automatic timeline event only once, even across incidents/retries.
CREATE UNIQUE INDEX incident_events_source_alert_type_idx
    ON incident_events(organisation_id, source_alert_id, event_type) WHERE source_alert_id IS NOT NULL;

-- Unanchored Phase 4A incidents keep their existing association semantics.
CREATE FUNCTION validate_incident_alert_service() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE incident_service UUID; alert_service UUID;
BEGIN
    SELECT monitored_service_id INTO incident_service FROM incidents
        WHERE id = NEW.incident_id AND organisation_id = NEW.organisation_id;
    SELECT monitored_service_id INTO alert_service FROM alerts
        WHERE id = NEW.alert_id AND organisation_id = NEW.organisation_id;
    IF incident_service IS NOT NULL AND alert_service IS NOT NULL AND incident_service <> alert_service THEN
        RAISE EXCEPTION 'Incident and alert must reference the same monitored service' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER incident_alerts_validate_service BEFORE INSERT OR UPDATE ON incident_alerts
    FOR EACH ROW EXECUTE FUNCTION validate_incident_alert_service();
ALTER TABLE incident_alerts ENABLE ALWAYS TRIGGER incident_alerts_validate_service;
