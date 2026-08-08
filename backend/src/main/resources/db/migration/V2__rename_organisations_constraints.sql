ALTER TABLE organisations
    RENAME CONSTRAINT firstkey TO organisations_pkey;

ALTER TABLE organisations
    ADD CONSTRAINT organisations_slug_key UNIQUE (slug);