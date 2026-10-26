CREATE TABLE alert_rules (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    monitored_service_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    consecutive_failures INTEGER NOT NULL DEFAULT 3,
    consecutive_recoveries INTEGER NOT NULL DEFAULT 2,
    severity VARCHAR(8) NOT NULL DEFAULT 'HIGH',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT alert_rules_monitored_service_key UNIQUE (monitored_service_id),
    CONSTRAINT alert_rules_service_organisation_fk
        FOREIGN KEY (monitored_service_id, organisation_id)
        REFERENCES monitored_services (id, organisation_id) ON DELETE CASCADE,
    CONSTRAINT alert_rules_failures_check CHECK (consecutive_failures BETWEEN 1 AND 20),
    CONSTRAINT alert_rules_recoveries_check CHECK (consecutive_recoveries BETWEEN 1 AND 20),
    CONSTRAINT alert_rules_severity_check CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT alert_rules_timestamps_check CHECK (updated_at >= created_at)
);

CREATE INDEX alert_rules_organisation_id_idx ON alert_rules (organisation_id);
