create table organisations (
  ID UUID CONSTRAINT firstkey PRIMARY KEY ,
    name VARCHAR(40) NOT NULL ,
    slug VARCHAR(120) NOT NULL ,
    status VARCHAR(30) NOT NULL ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
