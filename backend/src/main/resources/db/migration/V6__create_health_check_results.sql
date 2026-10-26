ALTER TABLE monitored_services
    ADD CONSTRAINT monitored_services_id_organisation_key UNIQUE (id, organisation_id);

CREATE TABLE health_check_results (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    monitored_service_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(7) NOT NULL CHECK (status IN ('UP', 'DOWN', 'UNKNOWN')),
    http_status_code INTEGER CHECK (http_status_code BETWEEN 100 AND 599),
    response_time_ms BIGINT NOT NULL CHECK (response_time_ms >= 0),
    failure_detail VARCHAR(255),
    CONSTRAINT health_check_results_service_organisation_fk
        FOREIGN KEY (monitored_service_id, organisation_id)
        REFERENCES monitored_services (id, organisation_id) ON DELETE CASCADE,
    CONSTRAINT health_check_results_timestamp_check CHECK (completed_at >= started_at)
);

CREATE INDEX health_check_results_service_history_idx
    ON health_check_results (organisation_id, monitored_service_id, started_at DESC, id DESC);
