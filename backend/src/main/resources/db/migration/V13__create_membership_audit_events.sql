-- Membership administration receipts survive membership removal and contain no credentials.
CREATE TABLE membership_audit_events (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    membership_id UUID NOT NULL,
    target_user_id UUID NOT NULL REFERENCES users(id),
    actor_user_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(16) NOT NULL,
    previous_role VARCHAR(20),
    new_role VARCHAR(20),
    occurred_at TIMESTAMPTZ NOT NULL,
    -- No FK to the membership: REMOVED receipts must outlive it.
    CONSTRAINT membership_audit_action_check CHECK (action IN ('ADDED', 'ROLE_CHANGED', 'REMOVED')),
    CONSTRAINT membership_audit_previous_role_check CHECK (previous_role IN ('OWNER','ADMIN','ENGINEER','VIEWER')),
    CONSTRAINT membership_audit_new_role_check CHECK (new_role IN ('OWNER','ADMIN','ENGINEER','VIEWER')),
    CONSTRAINT membership_audit_transition_check CHECK (
        (action = 'ADDED' AND previous_role IS NULL AND new_role IS NOT NULL)
        OR (action = 'REMOVED' AND previous_role IS NOT NULL AND new_role IS NULL)
        OR (action = 'ROLE_CHANGED' AND previous_role IS NOT NULL AND new_role IS NOT NULL AND previous_role <> new_role))
);
CREATE INDEX membership_audit_organisation_history_idx
    ON membership_audit_events(organisation_id, occurred_at DESC, id DESC);
CREATE INDEX membership_audit_target_idx ON membership_audit_events(target_user_id);
CREATE INDEX membership_audit_actor_idx ON membership_audit_events(actor_user_id);

CREATE FUNCTION reject_membership_audit_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Membership audit events are append-only' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER membership_audit_no_mutation BEFORE UPDATE OR DELETE ON membership_audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_membership_audit_mutation();
CREATE TRIGGER membership_audit_no_truncate BEFORE TRUNCATE ON membership_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_membership_audit_mutation();
ALTER TABLE membership_audit_events ENABLE ALWAYS TRIGGER membership_audit_no_mutation;
ALTER TABLE membership_audit_events ENABLE ALWAYS TRIGGER membership_audit_no_truncate;
