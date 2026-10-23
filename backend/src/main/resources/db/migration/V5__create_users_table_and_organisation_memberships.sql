CREATE TABLE users (
                       id UUID PRIMARY KEY,
                       identity_provider_subject VARCHAR(255) NOT NULL,
                       display_name VARCHAR(100) NOT NULL,
                       email VARCHAR(255) NOT NULL UNIQUE,
                       status VARCHAR(20) NOT NULL,

                       CONSTRAINT uk_users_identity_provider_subject
                           UNIQUE (identity_provider_subject)
);

CREATE TABLE organisation_memberships (
                                          id UUID PRIMARY KEY,
                                          organisation_id UUID NOT NULL,
                                          user_id UUID NOT NULL,
                                          role VARCHAR(20) NOT NULL,

                                          CONSTRAINT fk_membership_organisation
                                              FOREIGN KEY (organisation_id)
                                                  REFERENCES organisations(id),

                                          CONSTRAINT fk_membership_user
                                              FOREIGN KEY (user_id)
                                                  REFERENCES users(id),

                                          CONSTRAINT uk_membership_organisation_user
                                              UNIQUE (organisation_id, user_id)
);

CREATE INDEX idx_organisation_memberships_user_id
    ON organisation_memberships(user_id);