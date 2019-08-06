/*
 * This SQL script creates a database that contains metadata quality
 * reports that are generated by the metadata quality engine.
 */

/* CREATE SEQUENCE id_seq; */

CREATE USER metadig;

CREATE DATABASE metadig OWNER metadig;

/* alter database metadig owner to metadig; */

\connect metadig

CREATE TABLE identifiers (
  metadata_id TEXT not null,
  data_source TEXT not null,
  CONSTRAINT metadata_id_pk PRIMARY KEY (metadata_id)
);

alter table identifiers owner to metadig;

create table nodes {
  node_id TEXT not null,
  last_harvest_datetime TEXT not null,
  CONSTRAINT node_id_pk PRIMARY KEY (node_id)
}

alter table nodes owner to metadig;

create TABLE runs (
  metadata_id TEXT not null,
  suite_id TEXT not null,
  timestamp TIMESTAMP WITH TIME ZONE,
  results TEXT not null,
  error TEXT not null,
  status TEXT not null,
  sequenceId TEXT,
  CONSTRAINT runs_metadata_id_fk FOREIGN KEY (metadata_id) REFERENCES identifiers,
  CONSTRAINT metadata_id_suite_id_fk UNIQUE (metadata_id, suite_id)
);

alter table runs owner to metadig;
