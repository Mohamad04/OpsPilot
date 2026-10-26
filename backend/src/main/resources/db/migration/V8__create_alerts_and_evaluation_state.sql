-- Required by tenant/service-scoped evidence foreign keys. No existing observations are rewritten.
ALTER TABLE health_check_results ADD CONSTRAINT health_check_results_identity_scope_key
    UNIQUE (id, organisation_id, monitored_service_id);

CREATE TABLE alerts (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    monitored_service_id UUID NOT NULL,
    category VARCHAR(32) NOT NULL CHECK (category = 'HTTP_AVAILABILITY'),
    severity VARCHAR(8) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status VARCHAR(8) NOT NULL CHECK (status IN ('ACTIVE', 'RESOLVED')),
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    occurrence_count BIGINT NOT NULL,
    failure_threshold INTEGER NOT NULL CHECK (failure_threshold BETWEEN 1 AND 20),
    recovery_threshold INTEGER NOT NULL CHECK (recovery_threshold BETWEEN 1 AND 20),
    resolved_after_recoveries INTEGER CHECK (resolved_after_recoveries BETWEEN 1 AND 20),
    first_failure_health_check_result_id UUID NOT NULL,
    triggering_health_check_result_id UUID NOT NULL,
    last_failure_health_check_result_id UUID NOT NULL,
    resolution_health_check_result_id UUID,
    triggering_http_status_code INTEGER CHECK (triggering_http_status_code BETWEEN 100 AND 599),
    triggering_response_time_ms BIGINT NOT NULL CHECK (triggering_response_time_ms >= 0),
    triggering_failure_detail VARCHAR(255),
    CONSTRAINT alerts_service_organisation_fk FOREIGN KEY (monitored_service_id, organisation_id)
        REFERENCES monitored_services (id, organisation_id) ON DELETE CASCADE,
    CONSTRAINT alerts_first_evidence_fk FOREIGN KEY (first_failure_health_check_result_id, organisation_id, monitored_service_id)
        REFERENCES health_check_results (id, organisation_id, monitored_service_id),
    CONSTRAINT alerts_trigger_evidence_fk FOREIGN KEY (triggering_health_check_result_id, organisation_id, monitored_service_id)
        REFERENCES health_check_results (id, organisation_id, monitored_service_id),
    CONSTRAINT alerts_last_evidence_fk FOREIGN KEY (last_failure_health_check_result_id, organisation_id, monitored_service_id)
        REFERENCES health_check_results (id, organisation_id, monitored_service_id),
    CONSTRAINT alerts_resolution_evidence_fk FOREIGN KEY (resolution_health_check_result_id, organisation_id, monitored_service_id)
        REFERENCES health_check_results (id, organisation_id, monitored_service_id),
    CONSTRAINT alerts_occurrences_check CHECK (occurrence_count >= failure_threshold),
    CONSTRAINT alerts_timestamps_check CHECK (last_seen_at >= first_seen_at),
    CONSTRAINT alerts_resolution_check CHECK (
        (status = 'ACTIVE' AND resolved_at IS NULL AND resolution_health_check_result_id IS NULL AND resolved_after_recoveries IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolved_at >= last_seen_at
            AND resolution_health_check_result_id IS NOT NULL AND resolved_after_recoveries IS NOT NULL))
);

CREATE UNIQUE INDEX alerts_one_active_service_category_idx
    ON alerts (monitored_service_id, category) WHERE status = 'ACTIVE';
CREATE INDEX alerts_organisation_service_history_idx
    ON alerts (organisation_id, monitored_service_id, first_seen_at DESC);

CREATE TABLE alert_evaluation_states (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    monitored_service_id UUID NOT NULL,
    consecutive_failures INTEGER NOT NULL CHECK (consecutive_failures >= 0),
    consecutive_recoveries INTEGER NOT NULL CHECK (consecutive_recoveries >= 0),
    first_failure_health_check_result_id UUID,
    first_failure_at TIMESTAMPTZ,
    last_processed_health_check_result_id UUID,
    last_processed_started_at TIMESTAMPTZ,
    configuration_key VARCHAR(64) NOT NULL CHECK (configuration_key ~ '^[0-9a-f]{64}$'),
    configuration_changed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT alert_evaluation_states_service_key UNIQUE (monitored_service_id),
    CONSTRAINT alert_evaluation_states_service_fk FOREIGN KEY (monitored_service_id, organisation_id)
        REFERENCES monitored_services (id, organisation_id) ON DELETE CASCADE,
    CONSTRAINT alert_evaluation_states_first_evidence_fk FOREIGN KEY (first_failure_health_check_result_id, organisation_id, monitored_service_id)
        REFERENCES health_check_results (id, organisation_id, monitored_service_id),
    CONSTRAINT alert_evaluation_states_last_evidence_fk FOREIGN KEY (last_processed_health_check_result_id, organisation_id, monitored_service_id)
        REFERENCES health_check_results (id, organisation_id, monitored_service_id),
    CONSTRAINT alert_evaluation_states_streak_check CHECK (consecutive_failures = 0 OR consecutive_recoveries = 0),
    CONSTRAINT alert_evaluation_states_cursor_check CHECK (
        (last_processed_health_check_result_id IS NULL AND last_processed_started_at IS NULL
            AND consecutive_failures = 0 AND consecutive_recoveries = 0)
        OR (last_processed_health_check_result_id IS NOT NULL AND last_processed_started_at IS NOT NULL)),
    CONSTRAINT alert_evaluation_states_failure_check CHECK (
        (consecutive_failures = 0 AND first_failure_health_check_result_id IS NULL AND first_failure_at IS NULL)
        OR (consecutive_failures > 0 AND first_failure_health_check_result_id IS NOT NULL
            AND first_failure_at IS NOT NULL AND first_failure_at <= last_processed_started_at))
);
