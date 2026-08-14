CREATE TABLE monitored_services (
                                    id UUID CONSTRAINT monitored_services_pkey PRIMARY KEY,
                                    organisation_id UUID NOT NULL
                                        CONSTRAINT monitored_services_organisation_fk
                                            REFERENCES organisations(id),
                                    name VARCHAR(120) NOT NULL,
                                    service_type VARCHAR(50) NOT NULL,
                                    environment VARCHAR(30) NOT NULL,
                                    base_url VARCHAR(2048) NOT NULL,
                                    health_endpoint VARCHAR(2048) NOT NULL,
                                    owner VARCHAR(100) NOT NULL,
                                    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX monitored_services_organisation_id_idx
    ON monitored_services (organisation_id);