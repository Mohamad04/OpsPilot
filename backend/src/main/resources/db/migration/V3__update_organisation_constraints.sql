ALTER TABLE organisations
    ALTER COLUMN name TYPE VARCHAR(100),
    ALTER COLUMN slug TYPE VARCHAR(100);

ALTER TABLE organisations
add constraint organisations_name_not_blank
    CHECK ( btrim(name) <> ''),
ADD constraint organisations_slug_min_length
    CHECK ( char_length(slug) >= 2 ),
ADD constraint organisations_slug_format
    CHECK (SLUG ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
ADD constraint organisations_status_values
CHECK ( status IN ('ACTIVE', 'INACTIVE'))